package com.peergrab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S3 detail-cache benchmark. a/b are read-only closed-loop comparisons; c is
 * publish-then-read (9 reads of the newly published task per write); d targets
 * one hot detail key for 90% of reads. Cold cache requires a fresh bench stack.
 * A second trial on the same stack is warm, but cannot recreate a cold cache.
 *
 * dbLoads from /api/internal/cache-stats counts detail-cache fallbacks only.
 * It does not count authentication, publishing, or all MySQL queries.
 */
public final class CacheLoadClient {
    static final long SEED_BASE = 920_000_000_000L;
    static final int SEED_COUNT = 100;
    static final int DEFAULT_CONCURRENCY = 50;
    static final int DEFAULT_ITERATIONS = 100;
    static final long DEFAULT_SEED = 20260928L;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Config(String mode, String baseUrl, int concurrency, int iterations,
                  int trials, int durationSeconds, long seed) {}

    private CacheLoadClient() {}

    public static void main(String[] args) throws Exception {
        Config config = parse(args);
        BenchSafety.requireDisposableStack(config.baseUrl());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        String token = login(client, config.baseUrl(), 1001);
        String adminToken = login(client, config.baseUrl(), 9001);

        boolean allPassed = true;
        try (BenchRunRecorder recorder = new BenchRunRecorder()) {
            for (int trial = 1; trial <= config.trials(); trial++) {
                String runId = recorder.startRun("BENCH", "S3-" + config.mode(),
                        config.concurrency(), "closed-loop; trial=" + trial + "/" + config.trials()
                                + "; durationSeconds=" + config.durationSeconds()
                                + "; detail dbLoads is not total MySQL queries");
                System.out.printf("[run] runId=%s mode=%s trial=%d/%d%n",
                        runId, config.mode(), trial, config.trials());
                try {
                    boolean passed = runTrial(config, trial, runId, client, token, adminToken, recorder);
                    allPassed &= passed;
                    if (!passed) break;
                } catch (Exception e) {
                    recorder.finishRun(runId, "FAIL", "{}");
                    throw e;
                }
            }
        }
        if (!allPassed) System.exit(1);
    }

    static Config parse(String[] args) {
        String mode = args.length > 0 ? args[0] : "b";
        String baseUrl = args.length > 1 && !args[1].startsWith("--")
                ? args[1] : "http://127.0.0.1:8080";
        if (!List.of("a", "b", "c", "d").contains(mode)) {
            throw new IllegalArgumentException("mode must be a, b, c, or d");
        }
        int firstOption = args.length > 1 && !args[1].startsWith("--") ? 2 : 1;
        int concurrency = DEFAULT_CONCURRENCY;
        int iterations = DEFAULT_ITERATIONS;
        int trials = 1;
        int duration = 0;
        long seed = DEFAULT_SEED;
        boolean iterationsSpecified = false;
        for (int i = firstOption; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--concurrency=")) {
                concurrency = bounded(arg.substring(14), "concurrency", 1, 100);
            } else if (arg.startsWith("--iterations=")) {
                iterations = bounded(arg.substring(13), "iterations", 1, 10_000);
                iterationsSpecified = true;
            } else if (arg.startsWith("--trials=")) {
                trials = bounded(arg.substring(9), "trials", 1, 5);
            } else if (arg.startsWith("--duration-seconds=")) {
                duration = bounded(arg.substring(19), "duration-seconds", 1, 120);
            } else if (arg.startsWith("--seed=")) {
                try {
                    seed = Long.parseLong(arg.substring(7));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("seed must be a signed decimal long", e);
                }
            } else {
                throw new IllegalArgumentException("unknown S3 option: " + arg);
            }
        }
        if (duration > 0 && !iterationsSpecified) iterations = Integer.MAX_VALUE;
        if (mode.equals("c")) {
            if (duration > 0 || trials != 1 || iterations % 10 != 0
                    || (long) concurrency * iterations / 10 > 500) {
                throw new IllegalArgumentException(
                        "mode c requires one fixed-count trial, iterations divisible by 10, and <=500 publishes");
            }
        }
        return new Config(mode, baseUrl, concurrency, iterations, trials, duration, seed);
    }

    private static int bounded(String text, String name, int min, int max) {
        try {
            int value = Integer.parseInt(text);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
    }

    private static boolean runTrial(Config config, int trial, String runId,
                                    HttpClient client, String token, String adminToken,
                                    BenchRunRecorder recorder) throws Exception {
        requireOk(post(client, config.baseUrl(), "/api/internal/cache-stats/reset", adminToken, null));
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger workerFailures = new AtomicInteger();
        AtomicLong writes = new AtomicLong();
        AtomicLong firstPublishedId = new AtomicLong();
        List<Long> publishedIds = Collections.synchronizedList(new ArrayList<>());
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch ready = new CountDownLatch(config.concurrency());
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(config.concurrency());

        for (int t = 0; t < config.concurrency(); t++) {
            final int tid = t;
            Thread worker = new Thread(() -> {
                ready.countDown();
                try {
                    fire.await();
                    long deadline = config.durationSeconds() > 0
                            ? System.nanoTime() + TimeUnit.SECONDS.toNanos(config.durationSeconds())
                            : Long.MAX_VALUE;
                    runRound(config, client, token, tid, trial, runId, deadline,
                            ok, fail, writes, firstPublishedId, publishedIds, latencies);
                } catch (Exception e) {
                    workerFailures.incrementAndGet();
                    System.err.println("S3 worker stopped early: " + e);
                } finally {
                    done.countDown();
                }
            }, "s3-load-" + t);
            worker.start();
        }
        if (!ready.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("S3 workers did not become ready");
        }
        long started = System.nanoTime();
        fire.countDown();
        if (!done.await(600, TimeUnit.SECONDS)) {
            throw new IllegalStateException("S3 workers did not finish within 600 seconds");
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        // Run metadata is recorded after the timed phase; JDBC writes here must
        // not distort publish latency or contend on Recorder's one connection.
        recorder.trackErrands(runId, publishedIds);

        String statsBody = get(client, config.baseUrl(), "/api/internal/cache-stats", adminToken);
        JsonNode stats = requireOk(statsBody).path("data");
        if (!stats.has("requests") || !stats.has("dbLoads") || !stats.has("cacheHits")) {
            throw new IllegalStateException("cache-stats lacks required detail counters");
        }
        long total = (long) ok.get() + fail.get();
        long expected = (long) config.concurrency() * config.iterations();
        long detailReads = total - writes.get();
        boolean probePassed = true;
        int sampledDiffs = -1;
        if (config.mode().equals("c")) {
            long id = firstPublishedId.get();
            probePassed = id > 0 && readStatus(client, config.baseUrl(), token, id, "PUBLISHED")
                    && cancel(client, config.baseUrl(), token, id)
                    && readStatus(client, config.baseUrl(), token, id, "CANCELLED");
            sampledDiffs = requireOk(post(client, config.baseUrl(), "/api/internal/cache-check",
                    adminToken, null)).path("data").path("diffs").asInt(-1);
        }

        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        long p99 = sorted.isEmpty() ? -1 : pct(sorted, 99);
        long rps = Math.round(total * 1000.0 / Math.max(elapsedMs, 1));
        String cachePhase = trial == 1 ? "first-on-stack" : "subsequent-on-same-stack";
        boolean countValid = config.durationSeconds() > 0 ? total > 0 : total == expected;
        boolean passed = countValid && fail.get() == 0 && workerFailures.get() == 0
                && stats.path("requests").asLong(-1) == detailReads
                && (!config.mode().equals("c") || (writes.get() * 10 == total
                        && probePassed && sampledDiffs == 0));

        System.out.printf("S3-%s trial=%d phase=%s seed=%d requests=%d ok=%d fail=%d writes=%d elapsedMs=%d "
                        + "completedRps=%d P99=%dms detailReads=%d detailDbLoads=%d "
                        + "detailCacheHits=%d probe=%s sampledCacheDiffs=%d status=%s%n",
                config.mode(), trial, cachePhase, config.seed(), total, ok.get(), fail.get(), writes.get(), elapsedMs,
                rps, p99, detailReads, stats.path("dbLoads").asLong(),
                stats.path("cacheHits").asLong(), probePassed, sampledDiffs, passed ? "PASS" : "FAIL");
        System.out.println("detailDbLoads counts only detail fallback; total MySQL queries were not measured here.");

        String summary = String.format(java.util.Locale.ROOT,
                "{\"mode\":\"S3-%s\",\"trial\":%d,\"cachePhase\":\"%s\",\"seed\":%d,\"concurrency\":%d,\"iterations\":%d,"
                        + "\"durationSeconds\":%d,\"requests\":%d,\"elapsedMs\":%d,"
                        + "\"rps\":%d,\"p99Ms\":%d,\"ok\":%d,\"fail\":%d,\"writes\":%d,"
                        + "\"detailReads\":%d,\"detailDbLoads\":%d,\"detailCacheHits\":%d,"
                        + "\"consistencyProbePassed\":%s,\"sampledCacheDiffs\":%d,\"stats\":%s}",
                config.mode(), trial, cachePhase, config.seed(), config.concurrency(), config.iterations(),
                config.durationSeconds(), total, elapsedMs, rps, p99, ok.get(), fail.get(),
                writes.get(), detailReads, stats.path("dbLoads").asLong(),
                stats.path("cacheHits").asLong(), probePassed, sampledDiffs, statsBody);
        recorder.finishRun(runId, passed ? "PASS" : "FAIL", summary);
        return passed;
    }

    static boolean readDetail(HttpClient client, String baseUrl, String token, long id) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/errands/" + id))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 && resp.body().contains("\"code\":\"OK\"");
    }

    static boolean readStatus(HttpClient client, String baseUrl, String token,
                              long id, String expectedStatus) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/errands/" + id))
                .header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = JSON.readTree(response.body());
        return response.statusCode() == 200 && "OK".equals(root.path("code").asText())
                && expectedStatus.equals(root.path("data").path("status").asText());
    }

    static long publish(HttpClient client, String baseUrl, String token, String label) throws Exception {
        String body = "{\"title\":\"s3_write_" + label
                + "\",\"rewardCents\":100,\"slotTotal\":1}";
        JsonNode data = requireOk(post(client, baseUrl, "/api/errands", token, body)).path("data");
        String id = data.path("errandId").asText();
        if (!id.matches("[1-9][0-9]*")) {
            throw new IllegalStateException("published task response lacks a positive errandId");
        }
        return Long.parseLong(id);
    }

    static boolean cancel(HttpClient client, String baseUrl, String token, long id) throws Exception {
        JsonNode data = requireOk(post(client, baseUrl, "/api/errands/" + id + "/cancel",
                token, "{}")).path("data");
        return "REFUNDED".equals(data.path("result").asText());
    }

    static String login(HttpClient client, String baseUrl, long userId) throws Exception {
        String password = System.getenv("PEERGRAB_AUTH_DEMO_PASSWORD_" + userId);
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_AUTH_DEMO_PASSWORD_" + userId + " before running the benchmark");
        }
        String resp = post(client, baseUrl, "/api/auth/login", null,
                "{\"userId\":" + userId + ",\"password\":\"" + escapeJson(password) + "\"}");
        JsonNode data = requireOk(resp).path("data");
        String token = data.path("token").asText("");
        if (token.isBlank()) throw new IllegalStateException("Benchmark login lacks token for user " + userId);
        return token;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String post(HttpClient client, String baseUrl, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    static String get(HttpClient client, String baseUrl, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static JsonNode requireOk(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        if (!"OK".equals(root.path("code").asText())) {
            throw new IllegalStateException("S3 API returned " + root.path("code").asText("invalid JSON"));
        }
        return root;
    }

    static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    static void runRound(Config config, HttpClient client, String token, int tid,
                                 int trial, String runId, long deadline,
                                 AtomicInteger ok, AtomicInteger fail,
                                 AtomicLong writeCount, AtomicLong firstPublishedId,
                                 List<Long> publishedIds,
                                 List<Long> latencies) {
        long lastPublishedId = 0;
        SplittableRandom targets = new SplittableRandom(config.seed() + 1_000_003L * trial + tid);
        for (int r = 0; r < config.iterations() && System.nanoTime() < deadline; r++) {
            long started = System.nanoTime();
            try {
                boolean success;
                if (config.mode().equals("c") && r % 10 == 0) {
                    writeCount.incrementAndGet();
                    long id = publish(client, config.baseUrl(), token,
                            runId + "_" + trial + "_" + tid + "_" + r);
                    lastPublishedId = id;
                    firstPublishedId.compareAndSet(0, id);
                    publishedIds.add(id);
                    success = true;
                } else {
                    long id;
                    if (config.mode().equals("c")) {
                        id = lastPublishedId;
                    } else if (config.mode().equals("d")
                            && targets.nextInt(10) < 9) {
                        id = SEED_BASE + 1;
                    } else {
                        id = SEED_BASE + 1 + targets.nextInt(SEED_COUNT);
                    }
                    success = id > 0 && readDetail(client, config.baseUrl(), token, id);
                }
                if (success) ok.incrementAndGet(); else fail.incrementAndGet();
            } catch (Exception e) {
                fail.incrementAndGet();
            } finally {
                latencies.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            }
        }
    }
}
