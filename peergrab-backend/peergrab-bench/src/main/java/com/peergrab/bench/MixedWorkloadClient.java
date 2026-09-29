package com.peergrab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Fixed-arrival mixed HTTP workload on a verified disposable stack. S2 data must
 * first be installed with seed_bench.py s2; fixture creation is outside timing.
 *
 * Usage: MixedWorkloadClient <localBaseUrl> <rps> <warmupSeconds>
 *        <sampleSeconds> <maxInFlight> [timeoutMillis]
 *
 * The 20-request cycle is 4 cursor-first, 4 cursor-continuation, 10 detail,
 * and 2 publish requests. It models concurrent reads and writes, not a full
 * transaction journey or a whole-site capacity claim.
 */
public final class MixedWorkloadClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WRITERS = 16;
    private static final int READERS = 32;
    private static final int DETAIL_FIXTURES = 100;
    private static final long WRITER_BASE = 41_000L;
    private static final long READER_BASE = 42_000L;
    private static final int REWARD_CENTS = 100;
    private static final String LIST_PATH = "/api/errands?campusId=1&status=PUBLISHED&size=20&cursor=";

    enum Operation { LIST_FIRST, LIST_PAGE_5, LIST_PAGE_20, LIST_PAGE_100, DETAIL, PUBLISH }

    record Config(String baseUrl, int rps, int warmupSeconds, int sampleSeconds,
                  int maxInFlight, int timeoutMillis) { }

    private record RequestSpec(Operation operation, HttpRequest request, long expectedId) { }

    private MixedWorkloadClient() { }

    public static void main(String[] args) throws Exception {
        Config config = parse(args);
        BenchSafety.requireDisposableStack(config.baseUrl());
        BenchSafety.requireMaintenanceWindow();
        String dbHost = required("PEERGRAB_TEST_DB_HOST");
        String dbPort = required("PEERGRAB_TEST_DB_PORT");
        String jdbc = "jdbc:mysql://" + dbHost + ":" + dbPort
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String dbUser = System.getenv().getOrDefault("PEERGRAB_TEST_DB_USER", "root");
        String dbPassword = required("PEERGRAB_TEST_DB_PASSWORD");
        try (Connection db = DriverManager.getConnection(jdbc, dbUser, dbPassword);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbc, dbUser, dbPassword);
             ExecutorService executor = Executors.newFixedThreadPool(Math.min(config.maxInFlight(), 128))) {
            int seeded = requireSeed(db);
            HttpClient http = HttpClient.newBuilder().executor(executor)
                    .connectTimeout(Duration.ofMillis(Math.min(config.timeoutMillis(), 5_000)))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1).build();
            String runId = recorder.startRun("BENCH", "MIXED-OPEN", config.maxInFlight(),
                    "20% cursor first, 20% continuation, 50% detail, 10% publish; offered "
                            + config.rps() + "/s; sample " + config.sampleSeconds() + "s");
            boolean finished = false;
            try {
                BenchJwtTokens tokens = new BenchJwtTokens(required("PEERGRAB_AUTH_JWT_SECRET"), db);
                long capacity = ((long) config.rps() * (config.warmupSeconds() + config.sampleSeconds())
                        + 19) / 20 * 2;
                prepareWallets(db, Math.max(20_000, capacity * REWARD_CENTS / WRITERS + 20_000));
                List<String> writerTokens = new ArrayList<>(WRITERS);
                for (int i = 0; i < WRITERS; i++) writerTokens.add(tokens.issue(WRITER_BASE + i));
                List<String> readerTokens = new ArrayList<>(READERS);
                for (int i = 0; i < READERS; i++) readerTokens.add(tokens.issue(READER_BASE + i));
                List<Long> detailIds = prepareDetails(http, config, writerTokens.get(0), runId);
                recorder.trackErrands(runId, detailIds);
                List<String> cursors = prepareCursors(http, config, readerTokens.get(0));
                long walletBefore = scalar(db, "SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account");
                Fixture fixture = new Fixture(config, runId, writerTokens, readerTokens, detailIds,
                        cursors, new AtomicLong());
                Phase warmup = runPhase(http, fixture, config.warmupSeconds(), false);
                String admin = tokens.issue(9001L);
                requireOk(http.send(request(config, admin, "/api/internal/cache-stats/reset", "{}", null),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                Phase sample = runPhase(http, fixture, config.sampleSeconds(), true);
                JsonNode cacheStats = requireOk(http.send(
                        request(config, admin, "/api/internal/cache-stats", null, null),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
                long durable = durableWrites(db, runId);
                long walletAfter = scalar(db, "SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account");
                long debitCredit = signedLedgerSum(db);
                int expectedWrites = warmup.counter(Operation.PUBLISH).ok.get()
                        + sample.counter(Operation.PUBLISH).ok.get();
                boolean passed = warmup.clean() && sample.clean() && durable == expectedWrites
                        && walletAfter == walletBefore && debitCredit == 0
                        && cacheMetric(cacheStats, "requests")
                           == sample.counter(Operation.DETAIL).sent.get();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("runId", runId);
                summary.put("seededPublished", seeded);
                summary.put("readerUsers", READERS);
                summary.put("writerUsers", WRITERS);
                summary.put("cursorDepths", List.of(5, 20, 100));
                summary.put("rpsOffered", config.rps());
                summary.put("warmupSeconds", config.warmupSeconds());
                summary.put("sampleSeconds", config.sampleSeconds());
                summary.put("maxInFlight", config.maxInFlight());
                summary.put("warmup", warmup.summary());
                summary.put("sample", sample.summary());
                Map<String, Long> safeCacheStats = new LinkedHashMap<>();
                for (String field : List.of("requests", "dbLoads", "cacheHits",
                        "staleReturns", "degradedReads")) {
                    safeCacheStats.put(field, cacheMetric(cacheStats, field));
                }
                summary.put("detailCacheStats", safeCacheStats);
                summary.put("durablePublished", durable);
                summary.put("acknowledgedPublished", expectedWrites);
                summary.put("walletTotalDelta", walletAfter - walletBefore);
                summary.put("globalDebitCreditDiff", debitCredit);
                String json = JSON.writeValueAsString(summary);
                recorder.finishRun(runId, passed ? "PASS" : "FAIL", json);
                finished = true;
                System.out.println(json);
                if (!passed) throw new IllegalStateException("Mixed workload failed; see BENCH run " + runId);
            } catch (Exception e) {
                if (!finished) recorder.finishRun(runId, "FAIL", JSON.writeValueAsString(
                        Map.of("runId", runId, "reason", e.getClass().getSimpleName())));
                throw e;
            }
        }
    }

    static long cacheMetric(JsonNode stats, String field) {
        JsonNode value = stats.path(field);
        String raw = (value.isIntegralNumber() || value.isTextual()) ? value.asText() : "";
        if (!raw.matches("[0-9]{1,19}")) {
            throw new IllegalStateException("Invalid cache metric: " + field);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid cache metric: " + field, e);
        }
    }

    static Config parse(String[] args) {
        if (args.length < 5 || args.length > 6) throw new IllegalArgumentException(usage());
        URI uri = URI.create(args[0]);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getPath().replaceAll("/+$", "").length() != 0
                || !"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Expected a host-local HTTP origin without path or credentials");
        }
        int rate = bounded(args[1], 1, 5_000, "rps");
        int warmup = bounded(args[2], 10, 300, "warmupSeconds");
        int sample = bounded(args[3], 60, 3_600, "sampleSeconds");
        int inFlight = bounded(args[4], 1, 2_000, "maxInFlight");
        int timeout = args.length == 6 ? bounded(args[5], 100, 30_000, "timeoutMillis") : 5_000;
        if ((long) rate * Math.max(warmup, sample) > 1_000_000) {
            throw new IllegalArgumentException("At most 1,000,000 offered requests per phase");
        }
        return new Config(args[0].replaceAll("/+$", ""), rate, warmup, sample, inFlight, timeout);
    }

    static Operation chooseOperation(long index) {
        int slot = (int) (index % 20);
        if (slot < 4) return Operation.LIST_FIRST;
        if (slot < 8) return switch ((int) ((index / 20 + slot - 4) % 3)) {
            case 0 -> Operation.LIST_PAGE_5;
            case 1 -> Operation.LIST_PAGE_20;
            default -> Operation.LIST_PAGE_100;
        };
        if (slot < 18) return Operation.DETAIL;
        return Operation.PUBLISH;
    }

    private static String usage() {
        return "MixedWorkloadClient <localBaseUrl> <rps> <warmupSeconds> <sampleSeconds> "
                + "<maxInFlight> [timeoutMillis]";
    }

    private static int bounded(String raw, int min, int max, String name) {
        try {
            int value = Integer.parseInt(raw);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be " + min + ".." + max);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Set " + name);
        return value;
    }

    private static int requireSeed(Connection db) throws Exception {
        try (PreparedStatement statement = db.prepareStatement("""
                SELECT COUNT(*) FROM errand WHERE id > 930000000000 AND id <= 930000100000
                  AND campus_id=1 AND status='PUBLISHED'
                """); ResultSet rows = statement.executeQuery()) {
            rows.next();
            int seeded = rows.getInt(1);
            if (seeded < 2_500) throw new IllegalStateException(
                    "Seed at least 2,500 S2 tasks using seed_bench.py s2 on a fresh disposable stack");
            return seeded;
        }
    }

    private static void prepareWallets(Connection db, long eachBalance) throws Exception {
        try (PreparedStatement check = db.prepareStatement(
                "SELECT COUNT(*) FROM wallet_account WHERE owner_id BETWEEN ? AND ? AND owner_type='USER'")) {
            check.setLong(1, WRITER_BASE);
            check.setLong(2, WRITER_BASE + WRITERS - 1);
            try (ResultSet rows = check.executeQuery()) {
                rows.next();
                if (rows.getInt(1) != 0) throw new IllegalStateException(
                        "Mixed workload writer fixture IDs already exist; use a fresh disposable stack");
            }
        }
        try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', ?, 0, 0)
                """)) {
            for (int i = 0; i < WRITERS; i++) {
                insert.setLong(1, WRITER_BASE + i);
                insert.setLong(2, WRITER_BASE + i);
                insert.setLong(3, eachBalance);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static List<Long> prepareDetails(HttpClient http, Config cfg, String publisher,
                                             String runId) throws Exception {
        List<Long> ids = new ArrayList<>(DETAIL_FIXTURES);
        for (int i = 0; i < DETAIL_FIXTURES; i++) {
            String title = "bm_" + runId + "_seed_" + i;
            JsonNode data = requireOk(http.send(publishRequest(cfg, publisher, title),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            long id = data.path("errandId").asLong();
            if (id <= 0) throw new IllegalStateException("Detail fixture publish lacked an ID");
            ids.add(id);
        }
        return ids;
    }

    private static List<String> prepareCursors(HttpClient http, Config cfg, String token)
            throws Exception {
        List<String> chosen = new ArrayList<>(3);
        String cursor = "";
        for (int page = 0; page <= 100; page++) {
            HttpRequest req = request(cfg, token, LIST_PATH + cursor, null, null);
            JsonNode data = requireOk(http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
            if (!data.path("items").isArray() || data.path("items").isEmpty()) {
                throw new IllegalStateException("S2 fixture has too few cursor pages");
            }
            String next = data.path("nextCursor").asText("");
            if (next.isBlank()) throw new IllegalStateException("S2 fixture ended before page 100");
            if (page == 5 || page == 20 || page == 100) chosen.add(next);
            cursor = next;
        }
        return chosen;
    }

    private static JsonNode requireOk(HttpResponse<String> response) throws Exception {
        JsonNode root = JSON.readTree(response.body());
        if (response.statusCode() != 200 || !"OK".equals(root.path("code").asText())) {
            throw new IllegalStateException("Benchmark fixture API rejected a request");
        }
        return root.path("data");
    }

    private static HttpRequest publishRequest(Config cfg, String token, String title) {
        String body = "{\"campusId\":1,\"type\":\"DELIVERY\",\"title\":\"" + title
                + "\",\"rewardCents\":" + REWARD_CENTS + ",\"slotTotal\":1}";
        return request(cfg, token, "/api/errands", body, UUID.randomUUID().toString());
    }

    private static HttpRequest request(Config cfg, String token, String path, String body, String requestId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofMillis(cfg.timeoutMillis()));
        if (body == null) return builder.GET().build();
        builder.header("Content-Type", "application/json");
        if (requestId != null) builder.header("X-Request-Id", requestId);
        return builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    }

    private record Fixture(Config config, String runId, List<String> writers,
                           List<String> readers, List<Long> detailIds, List<String> cursors,
                           AtomicLong writeIndex) {
        RequestSpec at(long index) {
            Operation operation = chooseOperation(index);
            String reader = readers.get((int) (index % readers.size()));
            return switch (operation) {
                case LIST_FIRST -> new RequestSpec(operation,
                        request(config, reader, LIST_PATH, null, null), 0);
                case LIST_PAGE_5, LIST_PAGE_20, LIST_PAGE_100 -> {
                    int depth = switch (operation) {
                        case LIST_PAGE_5 -> 0;
                        case LIST_PAGE_20 -> 1;
                        default -> 2;
                    };
                    yield new RequestSpec(operation,
                            request(config, reader, LIST_PATH + cursors.get(depth), null, null), 0);
                }
                case DETAIL -> {
                    long id = detailIds.get((int) (((index / 20) * 10 + index % 20 - 8)
                            % detailIds.size()));
                    yield new RequestSpec(operation,
                            request(config, reader, "/api/errands/" + id, null, null), id);
                }
                case PUBLISH -> {
                    String writer = writers.get((int) (writeIndex.getAndIncrement() % writers.size()));
                    String title = "bm_" + runId + "_load_" + index;
                    yield new RequestSpec(operation, publishRequest(config, writer, title), 0);
                }
            };
        }
    }

    private static Phase runPhase(HttpClient http, Fixture fixture, int seconds, boolean sample)
            throws InterruptedException {
        Config cfg = fixture.config();
        int planned = Math.multiplyExact(cfg.rps(), seconds);
        Phase phase = new Phase(planned, seconds, sample);
        Semaphore permits = new Semaphore(cfg.maxInFlight());
        AtomicInteger inFlight = new AtomicInteger();
        long started = System.nanoTime();
        long interval = TimeUnit.SECONDS.toNanos(1) / cfg.rps();
        long allowedLag = Math.max(1_000_000L, Math.min(100_000_000L, interval * 2));
        for (int i = 0; i < planned; i++) {
            long target = started + (long) i * TimeUnit.SECONDS.toNanos(1) / cfg.rps();
            parkUntil(target);
            phase.offered.incrementAndGet();
            if (System.nanoTime() - target > allowedLag) {
                phase.schedulerMissed.incrementAndGet();
                continue;
            }
            if (!permits.tryAcquire()) {
                phase.capacityRejected.incrementAndGet();
                continue;
            }
            RequestSpec spec = fixture.at(i + (sample ? 1_000_000L : 0));
            phase.sent.incrementAndGet();
            phase.counter(spec.operation()).sent.incrementAndGet();
            int active = inFlight.incrementAndGet();
            phase.peakInFlight.accumulateAndGet(active, Math::max);
            long requestStart = System.nanoTime();
            try {
                http.sendAsync(spec.request(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                        .whenComplete((response, error) -> {
                            try {
                                phase.record(spec, response, error,
                                        TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - requestStart));
                            } finally {
                                inFlight.decrementAndGet();
                                permits.release();
                            }
                        });
            } catch (RuntimeException e) {
                try {
                    phase.record(spec, null, e,
                            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - requestStart));
                } finally {
                    inFlight.decrementAndGet();
                    permits.release();
                }
            }
        }
        parkUntil(started + TimeUnit.SECONDS.toNanos(seconds));
        phase.completedWithinWindow = phase.completed.get();
        for (Counter counter : phase.byOperation.values()) {
            counter.completedWithinWindow = counter.completed.get();
        }
        phase.tailInFlight = inFlight.get();
        if (!permits.tryAcquire(cfg.maxInFlight(), cfg.timeoutMillis() + 30_000L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Mixed workload requests did not drain");
        }
        permits.release(cfg.maxInFlight());
        if (phase.sent.get() != phase.completed.get()) throw new IllegalStateException("Lost HTTP completions");
        return phase;
    }

    private static void parkUntil(long deadline) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
    }

    static boolean validResponse(Operation op, int status, JsonNode root, long expectedId) {
        if (status != 200 || root == null || !"OK".equals(root.path("code").asText())) return false;
        JsonNode data = root.path("data");
        return switch (op) {
            case LIST_FIRST, LIST_PAGE_5, LIST_PAGE_20, LIST_PAGE_100 -> data.path("items").isArray()
                    && !data.path("items").isEmpty()
                    && data.path("nextCursor").isTextual();
            case DETAIL -> String.valueOf(expectedId).equals(data.path("id").asText())
                    && "PUBLISHED".equals(data.path("status").asText());
            case PUBLISH -> data.path("errandId").asLong() > 0
                    && "PUBLISHED".equals(data.path("status").asText());
        };
    }

    private static long scalar(Connection db, String sql) throws Exception {
        try (PreparedStatement statement = db.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long durableWrites(Connection db, String runId) throws Exception {
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT COUNT(*) FROM errand WHERE title LIKE ? AND status='PUBLISHED'")) {
            statement.setString(1, "bm_" + runId + "_load_%");
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static long signedLedgerSum(Connection db) throws Exception {
        return scalar(db, "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0) "
                + "FROM wallet_ledger");
    }

    private static final class Phase {
        final int planned;
        final int seconds;
        final boolean capture;
        final AtomicInteger offered = new AtomicInteger();
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger schedulerMissed = new AtomicInteger();
        final AtomicInteger capacityRejected = new AtomicInteger();
        final AtomicInteger peakInFlight = new AtomicInteger();
        final EnumMap<Operation, Counter> byOperation = new EnumMap<>(Operation.class);
        volatile int completedWithinWindow;
        volatile int tailInFlight;

        Phase(int planned, int seconds, boolean capture) {
            this.planned = planned;
            this.seconds = seconds;
            this.capture = capture;
            for (Operation op : Operation.values()) byOperation.put(op, new Counter(capture));
        }

        Counter counter(Operation op) { return byOperation.get(op); }

        void record(RequestSpec spec, HttpResponse<String> response, Throwable error, long micros) {
            Counter counter = counter(spec.operation());
            counter.completed.incrementAndGet();
            completed.incrementAndGet();
            if (capture) counter.latencies.add(micros);
            if (error != null || response == null) {
                counter.transportErrors.incrementAndGet();
                return;
            }
            counter.statuses.computeIfAbsent(response.statusCode(), ignored -> new AtomicInteger())
                    .incrementAndGet();
            JsonNode root;
            try {
                root = JSON.readTree(response.body());
            } catch (Exception ignored) {
                counter.invalidJson.incrementAndGet();
                return;
            }
            String code = root.path("code").asText("MISSING");
            if (!code.matches("[A-Z_]{1,40}")) code = "OTHER";
            counter.businessCodes.computeIfAbsent(code, ignored -> new AtomicInteger()).incrementAndGet();
            if (validResponse(spec.operation(), response.statusCode(), root, spec.expectedId())) {
                counter.ok.incrementAndGet();
            } else {
                counter.mismatched.incrementAndGet();
            }
        }

        boolean clean() {
            if (offered.get() != planned || sent.get() != planned || completed.get() != planned
                    || schedulerMissed.get() != 0 || capacityRejected.get() != 0) return false;
            for (Counter c : byOperation.values()) {
                if (c.ok.get() != c.sent.get() || c.transportErrors.get() != 0
                        || c.mismatched.get() != 0 || c.invalidJson.get() != 0) return false;
            }
            return true;
        }

        Map<String, Object> summary() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("offered", offered.get());
            result.put("sent", sent.get());
            result.put("sentRps", Math.round(sent.get() * 1000.0 / seconds) / 1000.0);
            result.put("completed", completed.get());
            result.put("completedWithinWindow", completedWithinWindow);
            result.put("completedWithinWindowRps", Math.round(completedWithinWindow * 1000.0 / seconds) / 1000.0);
            result.put("schedulerMissed", schedulerMissed.get());
            result.put("capacityRejected", capacityRejected.get());
            result.put("peakInFlight", peakInFlight.get());
            result.put("tailInFlight", tailInFlight);
            Map<String, Object> operations = new LinkedHashMap<>();
            for (Operation op : Operation.values()) operations.put(op.name(), counter(op).summary(seconds));
            result.put("operations", operations);
            return result;
        }
    }

    private static final class Counter {
        final AtomicInteger sent = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger transportErrors = new AtomicInteger();
        final AtomicInteger invalidJson = new AtomicInteger();
        final AtomicInteger mismatched = new AtomicInteger();
        volatile int completedWithinWindow;
        final java.util.concurrent.ConcurrentHashMap<Integer, AtomicInteger> statuses =
                new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> businessCodes =
                new java.util.concurrent.ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> latencies;

        Counter(boolean capture) { latencies = capture ? new ConcurrentLinkedQueue<>() : null; }

        Map<String, Object> summary(int seconds) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sent", sent.get());
            result.put("completed", completed.get());
            result.put("completedWithinWindow", completedWithinWindow);
            result.put("completedWithinWindowRps",
                    Math.round(completedWithinWindow * 1000.0 / seconds) / 1000.0);
            result.put("ok", ok.get());
            result.put("transportErrors", transportErrors.get());
            result.put("invalidJson", invalidJson.get());
            result.put("businessMismatch", mismatched.get());
            Map<Integer, Integer> http = new java.util.TreeMap<>();
            statuses.forEach((key, value) -> http.put(key, value.get()));
            result.put("httpStatusCodes", http);
            Map<String, Integer> codes = new java.util.TreeMap<>();
            businessCodes.forEach((key, value) -> codes.put(key, value.get()));
            result.put("businessCodes", codes);
            if (latencies != null) {
                long[] sorted = latencies.stream().mapToLong(Long::longValue).toArray();
                Arrays.sort(sorted);
                result.put("p50Ms", percentileMillis(sorted, 50));
                result.put("p95Ms", percentileMillis(sorted, 95));
                result.put("p99Ms", percentileMillis(sorted, 99));
                result.put("maxMs", sorted.length == 0 ? null : sorted[sorted.length - 1] / 1000.0);
            }
            return result;
        }
    }

    static Double percentileMillis(long[] sortedMicros, int percent) {
        if (sortedMicros.length == 0) return null;
        int index = Math.max(0, (int) Math.ceil(sortedMicros.length * percent / 100.0) - 1);
        return sortedMicros[index] / 1000.0;
    }
}
