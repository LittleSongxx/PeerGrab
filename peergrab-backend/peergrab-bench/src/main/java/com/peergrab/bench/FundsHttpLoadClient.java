package com.peergrab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * S4 HTTP benchmark against a disposable JWT-mode stack. Preparation runs the public
 * publish/grab/confirm/pickup/deliver path; only settle requests enter the timed phases.
 * The database, rather than HTTP response counts, is the source of truth for money.
 *
 * <pre>
 * PEERGRAB_BENCH_DISPOSABLE=YES PEERGRAB_BENCH_PROJECT=peergrab-bench-local \
 * PEERGRAB_TEST_DB_HOST=127.0.0.1 PEERGRAB_TEST_DB_PORT=3307 \
 * PEERGRAB_AUTH_JWT_SECRET=... java -cp ... com.peergrab.bench.FundsHttpLoadClient \
 * http://127.0.0.1:28080 10 4 8
 * </pre>
 */
public final class FundsHttpLoadClient {
    private static final long PUBLISHER = 1001L;
    private static final long RUNNER = 2001L;
    private static final long ARBITRATOR = 9001L;
    private static final int REWARD_CENTS = 100;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Config(String baseUrl, int count, int concurrency, int sameTaskAttempts, int timeoutMillis) {}
    record Reply(int status, String code, JsonNode data, long latencyMicros) {}
    record Sample(long latencyMicros, boolean responseReceived, String outcome, String detail) {}
    record Phase(String name, int offered, int completed, int settled, int duplicateRejected,
                 int businessRejected,
                 int systemErrors, long elapsedNanos, List<Long> sortedLatencyMicros,
                 List<String> errorSamples) {
        double completedRps() { return completed * 1_000_000_000.0 / Math.max(1L, elapsedNanos); }
        long percentile(int p) {
            if (sortedLatencyMicros.isEmpty()) return -1;
            int index = (int) Math.ceil(sortedLatencyMicros.size() * p / 100.0) - 1;
            return sortedLatencyMicros.get(Math.max(0, index));
        }
    }

    private FundsHttpLoadClient() {}

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);
        BenchSafety.requireDisposableStack(cfg.baseUrl());
        String secret = System.getenv("PEERGRAB_AUTH_JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_AUTH_JWT_SECRET for the isolated JWT-mode stack");
        }
        BenchJwtTokens tokens = new BenchJwtTokens(secret);
        String publisher = tokens.issue(PUBLISHER);
        String runner = tokens.issue(RUNNER);
        String arbitrator = tokens.issue(ARBITRATOR);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.min(5_000, cfg.timeoutMillis())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();

        String host = System.getenv("PEERGRAB_TEST_DB_HOST");
        String port = System.getenv("PEERGRAB_TEST_DB_PORT");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String user = System.getenv().getOrDefault("PEERGRAB_TEST_DB_USER", "root");
        String password = System.getenv("PEERGRAB_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_TEST_DB_PASSWORD for database reconciliation");
        }
        try (Connection db = DriverManager.getConnection(jdbcUrl, user, password);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbcUrl, user, password)) {
            long totalBefore = scalar(db, "SELECT COALESCE(SUM(available + frozen),0) FROM wallet_account");
            long publisherBefore = balance(db, PUBLISHER);
            long runnerBefore = balance(db, RUNNER);
            if (publisherBefore < (cfg.count() + 3L) * REWARD_CENTS) {
                throw new IllegalStateException("Benchmark publisher balance is too low for requested task count");
            }
            String runId = recorder.startRun("BENCH", "S4-HTTP", cfg.concurrency(),
                    "HTTP lifecycle preparation; distinct and same-task settle timed; "
                            + "count=" + cfg.count() + "; rewardCents=" + REWARD_CENTS);
            List<Long> distinct = new ArrayList<>();
            List<Long> allIds = new ArrayList<>();
            boolean passed = false;
            String summary = "{}";
            try {
                requireOk(call(http, cfg, "GET", "/api/health", null, null, null), "health");
                // Serial preparation keeps the one demo runner below max-ongoing=5.
                for (int i = 0; i < cfg.count(); i++) {
                    long id = prepareDelivered(http, cfg, publisher, runner, runId, "distinct-" + i);
                    recorder.trackErrand(runId, id);
                    distinct.add(id);
                    allIds.add(id);
                }
                Phase many = runPhase(http, cfg, "distinct", distinct, publisher, cfg.concurrency());
                long distinctPersisted = count(db, distinct,
                        "SELECT COUNT(*) FROM errand WHERE id=? AND status='SETTLED'");
                double durableSettledTps = distinctPersisted * 1_000_000_000.0 /
                        Math.max(1L, many.elapsedNanos());
                long sameId = prepareDelivered(http, cfg, publisher, runner, runId, "same-task");
                recorder.trackErrand(runId, sameId);
                allIds.add(sameId);
                Phase same = runPhase(http, cfg, "same-task",
                        Collections.nCopies(cfg.sameTaskAttempts(), sameId), publisher,
                        cfg.sameTaskAttempts());

                long cancelled = publish(http, cfg, publisher, runId, "refund");
                recorder.trackErrand(runId, cancelled);
                allIds.add(cancelled);
                requireOk(call(http, cfg, "POST", path(cancelled, "cancel"), publisher, "{}", null), "cancel");
                long disputed = publish(http, cfg, publisher, runId, "arbitration");
                recorder.trackErrand(runId, disputed);
                allIds.add(disputed);
                requireOk(call(http, cfg, "POST", path(disputed, "grab"), runner, "{}", UUID.randomUUID().toString()), "arbitration grab");
                requireOk(call(http, cfg, "POST", path(disputed, "confirm"), runner, "{}", null), "arbitration confirm");
                requireOk(call(http, cfg, "POST", path(disputed, "dispute"), publisher, "{}", null), "dispute");
                requireOk(call(http, cfg, "POST", path(disputed, "arbitrate"), arbitrator,
                        "{\"favor\":\"PUBLISHER\"}", null), "arbitrate refund");

                long dbSettled = count(db, allIds, "SELECT COUNT(*) FROM errand WHERE id=? AND status='SETTLED'");
                long released = count(db, allIds, "SELECT COUNT(*) FROM escrow_order WHERE errand_id=? AND status='RELEASED'");
                long refunded = count(db, allIds, "SELECT COUNT(*) FROM escrow_order WHERE errand_id=? AND status='REFUNDED'");
                long badLedger = countBadLedgers(db, distinct, sameId, cancelled, disputed);
                long totalAfter = scalar(db, "SELECT COALESCE(SUM(available + frozen),0) FROM wallet_account");
                long globalDebitCredit = scalar(db, "SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END),0) FROM wallet_ledger");
                long systemSnapshotDiffs = scalar(db, """
                        SELECT COUNT(*) FROM wallet_account a WHERE a.owner_type IN ('ESCROW','COMMISSION')
                        AND a.available+a.frozen <> COALESCE((SELECT SUM(CASE WHEN l.direction='CREDIT'
                        THEN l.amount ELSE -l.amount END) FROM wallet_ledger l WHERE l.account_id=a.id),0)
                        """);
                long publisherAfter = balance(db, PUBLISHER);
                long runnerAfter = balance(db, RUNNER);
                print(many);
                print(same);
                System.out.printf(Locale.ROOT, "distinctDurableSettled=%d durableSettledTps=%.2f%n",
                        distinctPersisted, durableSettledTps);
                System.out.printf(Locale.ROOT, "DB settled=%d released=%d refunded=%d badLedger=%d "
                        + "globalDebitCredit=%d systemSnapshotDiffs=%d totalDelta=%d "
                        + "publisherDelta=%d runnerDelta=%d%n", dbSettled, released, refunded,
                        badLedger, globalDebitCredit, systemSnapshotDiffs, totalAfter - totalBefore,
                        publisherAfter - publisherBefore, runnerAfter - runnerBefore);
                // A timeout can still commit. Correctness therefore follows durable DB state.
                passed = distinctPersisted == cfg.count()
                        && dbSettled == cfg.count() + 1L && released == dbSettled
                        && refunded == 2 && badLedger == 0 && globalDebitCredit == 0
                        && systemSnapshotDiffs == 0 && totalBefore == totalAfter
                        && publisherAfter - publisherBefore == -(cfg.count() + 1L) * REWARD_CENTS
                        && runnerAfter > runnerBefore
                        && many.systemErrors() == 0 && same.systemErrors() == 0
                        && many.businessRejected() == 0 && same.settled() == 1
                        && same.duplicateRejected() == cfg.sameTaskAttempts() - 1;
                Map<String, Object> result = new LinkedHashMap<>();
                result.putAll(runParameters(cfg));
                Map<String, Object> distinctSummary = phaseSummary(many);
                distinctSummary.put("durableSettled", distinctPersisted);
                distinctSummary.put("durableSettledTps", durableSettledTps);
                result.put("distinct", distinctSummary);
                result.put("sameTask", phaseSummary(same));
                result.put("dbSettled", dbSettled);
                result.put("released", released);
                result.put("refunded", refunded);
                result.put("badLedger", badLedger);
                result.put("globalDebitCredit", globalDebitCredit);
                result.put("systemSnapshotDiffs", systemSnapshotDiffs);
                result.put("totalDelta", totalAfter - totalBefore);
                result.put("publisherDelta", publisherAfter - publisherBefore);
                result.put("runnerDelta", runnerAfter - runnerBefore);
                summary = JSON.writeValueAsString(result);
                if (!passed) throw new IllegalStateException("S4 HTTP correctness or error gate failed");
            } finally {
                recorder.finishRun(runId, passed ? "PASS" : "FAIL", summary);
                System.out.println("runId=" + runId + " status=" + (passed ? "PASS" : "FAIL"));
            }
        }
    }

    static Config parse(String[] args) {
        if (args.length < 3 || args.length > 5) throw new IllegalArgumentException(
                "Usage: FundsHttpLoadClient <baseUrl> <distinctCount> <concurrency> [sameTaskAttempts] [timeoutMillis]");
        URI uri = URI.create(args[0]);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !List.of("http", "https").contains(uri.getScheme())
                || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) origin without path or credentials");
        }
        int count = bounded(args[1], "distinctCount", 1, 500);
        int concurrency = bounded(args[2], "concurrency", 1, 64);
        int same = args.length >= 4 ? bounded(args[3], "sameTaskAttempts", 2, 200) : 8;
        int timeout = args.length >= 5 ? bounded(args[4], "timeoutMillis", 100, 30_000) : 10_000;
        return new Config(args[0].replaceAll("/+$", ""), count, concurrency, same, timeout);
    }

    private static int bounded(String text, String name, int min, int max) {
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
    }

    private static long prepareDelivered(HttpClient http, Config cfg, String publisher,
                                         String runner, String runId, String label) throws Exception {
        long id = publish(http, cfg, publisher, runId, label);
        requireOk(call(http, cfg, "POST", path(id, "grab"), runner, "{}", UUID.randomUUID().toString()), "grab");
        requireOk(call(http, cfg, "POST", path(id, "confirm"), runner, "{}", null), "confirm");
        requireOk(call(http, cfg, "POST", path(id, "pickup"), runner, "{}", null), "pickup");
        requireOk(call(http, cfg, "POST", path(id, "deliver"), runner, "{}", null), "deliver");
        return id;
    }

    private static long publish(HttpClient http, Config cfg, String publisher,
                                String runId, String label) throws Exception {
        String body = JSON.writeValueAsString(java.util.Map.of(
                "title", "s4_http_" + runId + "_" + label, "rewardCents", REWARD_CENTS, "slotTotal", 1));
        String requestId = UUID.randomUUID().toString();
        Reply reply;
        try {
            reply = call(http, cfg, "POST", "/api/errands", publisher, body, requestId);
        } catch (IOException e) {
            // Retry a lost response with the same idempotency key. Never use a fresh key here.
            reply = call(http, cfg, "POST", "/api/errands", publisher, body, requestId);
        }
        requireOk(reply, "publish");
        return parseErrandId(reply.data().path("errandId"));
    }

    static long parseErrandId(JsonNode id) {
        if (id == null || !(id.isIntegralNumber() || id.isTextual())) {
            throw new IllegalStateException("publish response lacks errandId");
        }
        String value = id.asText();
        if (!value.matches("[1-9][0-9]*")) {
            throw new IllegalStateException("publish response has invalid errandId");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("publish response errandId exceeds long range", e);
        }
    }

    private static String path(long id, String action) { return "/api/errands/" + id + "/" + action; }

    private static Reply call(HttpClient http, Config cfg, String method, String path,
                              String token, String body, String requestId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(cfg.baseUrl() + path))
                .timeout(Duration.ofMillis(cfg.timeoutMillis()));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (requestId != null) builder.header("X-Request-Id", requestId);
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body, StandardCharsets.UTF_8));
        } else builder.GET();
        long start = System.nanoTime();
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latency = (System.nanoTime() - start) / 1_000;
        JsonNode root = JSON.readTree(response.body());
        return new Reply(response.statusCode(), root.path("code").asText(""), root.path("data"), latency);
    }

    private static void requireOk(Reply reply, String step) {
        if (reply.status() != 200 || !"OK".equals(reply.code())) {
            throw new IllegalStateException(step + " failed: HTTP " + reply.status() + " code=" + reply.code());
        }
    }

    private static Phase runPhase(HttpClient http, Config cfg, String name, List<Long> ids,
                                  String publisher, int workers) throws Exception {
        List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(Math.min(workers, ids.size()));
        CountDownLatch fire = new CountDownLatch(1);
        long started;
        try (ExecutorService executor = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (long id : ids) {
                futures.add(executor.submit(() -> {
                    long attemptStart = 0;
                    try {
                        ready.countDown();
                        fire.await();
                        attemptStart = System.nanoTime();
                        Reply reply = call(http, cfg, "POST", path(id, "settle"), publisher, "{}", null);
                        String result = reply.data().path("result").asText("");
                        String outcome = classify(reply);
                        if (isDuplicateRejection(reply)) outcome = "DUPLICATE";
                        samples.add(new Sample(reply.latencyMicros(), true, outcome,
                                "HTTP " + reply.status() + " " + reply.code() + " " + result));
                    } catch (Exception e) {
                        long latency = attemptStart == 0 ? 0 : (System.nanoTime() - attemptStart) / 1_000;
                        samples.add(new Sample(latency, false, "SYSTEM", e.getClass().getSimpleName()));
                    }
                }));
            }
            if (!ready.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                fire.countDown();
                throw new IllegalStateException("HTTP benchmark workers did not become ready");
            }
            started = System.nanoTime();
            fire.countDown();
            for (Future<?> future : futures) future.get();
        }
        long elapsed = System.nanoTime() - started;
        List<Long> latencies = samples.stream().map(Sample::latencyMicros).sorted().toList();
        List<String> examples = samples.stream().filter(s -> s.outcome().equals("SYSTEM"))
                .map(Sample::detail).distinct().limit(5).toList();
        return new Phase(name, ids.size(),
                (int) samples.stream().filter(Sample::responseReceived).count(),
                (int) samples.stream().filter(s -> s.outcome().equals("SETTLED")).count(),
                (int) samples.stream().filter(s -> s.outcome().equals("DUPLICATE")).count(),
                (int) samples.stream().filter(s -> s.outcome().equals("BUSINESS")
                        || s.outcome().equals("DUPLICATE")).count(),
                (int) samples.stream().filter(s -> s.outcome().equals("SYSTEM")).count(),
                elapsed, latencies, examples);
    }

    private static void print(Phase phase) {
        System.out.printf(Locale.ROOT, "%s offered=%d completed=%d settled=%d duplicateRejected=%d businessRejected=%d "
                        + "systemErrors=%d completedRps=%.2f allAttemptP50/P95/P99=%d/%d/%d us errors=%s%n",
                phase.name(), phase.offered(), phase.completed(), phase.settled(), phase.duplicateRejected(),
                phase.businessRejected(), phase.systemErrors(), phase.completedRps(),
                phase.percentile(50), phase.percentile(95), phase.percentile(99), phase.errorSamples());
    }

    private static Map<String, Object> phaseSummary(Phase p) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offered", p.offered());
        result.put("completed", p.completed());
        result.put("settled", p.settled());
        result.put("duplicateRejected", p.duplicateRejected());
        result.put("businessRejected", p.businessRejected());
        result.put("systemErrors", p.systemErrors());
        result.put("completedRps", p.completedRps());
        result.put("allAttemptP50Us", p.percentile(50));
        result.put("allAttemptP95Us", p.percentile(95));
        result.put("allAttemptP99Us", p.percentile(99));
        result.put("elapsedSeconds", p.elapsedNanos() / 1_000_000_000.0);
        result.put("errorSamples", p.errorSamples());
        return result;
    }

    static Map<String, Object> runParameters(Config cfg) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", cfg.count());
        result.put("concurrency", cfg.concurrency());
        result.put("sameTaskAttempts", cfg.sameTaskAttempts());
        result.put("timeoutMillis", cfg.timeoutMillis());
        result.put("rewardCents", REWARD_CENTS);
        return result;
    }

    static String classify(Reply reply) {
        String result = reply.data().path("result").asText("");
        if (reply.status() == 200 && "OK".equals(reply.code()) && "SETTLED".equals(result)) {
            return "SETTLED";
        }
        if (reply.status() >= 200 && reply.status() < 500
                && (("OK".equals(reply.code()) && List.of("ALREADY_SETTLED", "CONFLICT").contains(result))
                    || (!"OK".equals(reply.code()) && !"INTERNAL_ERROR".equals(reply.code())))) {
            return "BUSINESS";
        }
        return "SYSTEM";
    }

    static boolean isDuplicateRejection(Reply reply) {
        String result = reply.data().path("result").asText("");
        return reply.status() < 500 && ("ALREADY_SETTLED".equals(result)
                || "CONFLICT".equals(result) || "SETTLE_CONFLICT".equals(reply.code()));
    }

    private static long scalar(Connection db, String sql) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long balance(Connection db, long userId) throws Exception {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT available+frozen FROM wallet_account WHERE owner_type='USER' AND owner_id=?")) {
            ps.setLong(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Missing bench wallet for " + userId);
                return rs.getLong(1);
            }
        }
    }

    private static long count(Connection db, List<Long> ids, String sql) throws Exception {
        long total = 0;
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            for (long id : ids) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); total += rs.getLong(1); }
            }
        }
        return total;
    }

    private static long countBadLedgers(Connection db, List<Long> distinct, long sameId,
                                        long cancelled, long disputed) throws Exception {
        long bad = 0;
        String sql = """
                SELECT COUNT(*),
                       COALESCE(SUM(direction='DEBIT'),0),
                       COALESCE(SUM(direction='CREDIT'),0),
                       COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE 0 END),0),
                       COALESCE(SUM(CASE WHEN direction='CREDIT' THEN amount ELSE 0 END),0)
                  FROM wallet_ledger WHERE ref_id=? AND ref_type=?
                """;
        try (PreparedStatement ps = db.prepareStatement(sql)) {
            List<Long> ids = new ArrayList<>(distinct);
            ids.addAll(List.of(sameId, cancelled, disputed));
            for (long id : ids) {
                for (String type : List.of("ESCROW", "SETTLE", "REFUND")) {
                    ps.setLong(1, id);
                    ps.setString(2, type);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        long rows = rs.getLong(1);
                        long debits = rs.getLong(2);
                        long credits = rs.getLong(3);
                        long debitCents = rs.getLong(4);
                        long creditCents = rs.getLong(5);
                        boolean settled = id != cancelled && id != disputed;
                        boolean expected = type.equals("ESCROW") || (settled && type.equals("SETTLE"))
                                || (!settled && type.equals("REFUND"));
                        if (expected && (debits != 1 || credits != rows - 1
                                || debitCents != REWARD_CENTS || creditCents != REWARD_CENTS
                                || (rows != 2 && !(type.equals("SETTLE") && rows == 3)))) bad++;
                        if (!expected && rows != 0) bad++;
                    }
                }
            }
        }
        return bad;
    }
}
