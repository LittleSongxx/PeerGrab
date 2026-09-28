package com.peergrab.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S1 尖峰抢单压测客户端。
 *
 * 历史客户端，保留旧轮次复现与发压器对照；新 S1 HTTP 负载使用
 * bench/jmeter/business/s1.jmx。CountDownLatch 同时放行虚拟线程，实际 send
 * 开始时间仍受客户端调度影响，因此本客户端单独记录放行到 send 的延迟。
 * 新旧工具的线程模型与到达分布不同，不能直接比较 P99 或宣称性能收益。
 *
 * Requires a disposable benchmark stack running JWT auth and its signing secret.
 *   PEERGRAB_BENCH_DISPOSABLE=YES PEERGRAB_BENCH_PROJECT=peergrab-bench-... \
 *   PEERGRAB_AUTH_JWT_SECRET=... java -cp ... SpikeLoadClient [baseUrl] [concurrency] [slotTotal]
 */
public class SpikeLoadClient {

    private static final String DEFAULT_BASE = "http://127.0.0.1:8080";
    private static final long PUBLISHER_ID = 1001L;

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : DEFAULT_BASE;
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        int slotTotal = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        BenchSafety.requireDisposableStack(baseUrl);
        if (concurrency < 1 || slotTotal < 1 || slotTotal > concurrency) {
            throw new IllegalArgumentException("Require 1 <= slotTotal <= concurrency");
        }
        String dbHost = System.getenv().getOrDefault("PEERGRAB_TEST_DB_HOST", "127.0.0.1");
        String dbPort = System.getenv().getOrDefault("PEERGRAB_TEST_DB_PORT", "3307");
        String dbPassword = System.getenv("PEERGRAB_TEST_DB_PASSWORD");
        if (dbPassword == null || dbPassword.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_TEST_DB_PASSWORD for benchmark auth sessions");
        }
        String jdbcUrl = "jdbc:mysql://" + dbHost + ":" + dbPort
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        // run_id 用于追溯和核验；清理只销毁经身份核验的整个独立栈。
        try (var db = java.sql.DriverManager.getConnection(jdbcUrl,
                System.getenv().getOrDefault("PEERGRAB_TEST_DB_USER", "root"), dbPassword);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbcUrl,
                     System.getenv().getOrDefault("PEERGRAB_TEST_DB_USER", "root"), dbPassword)) {
        BenchJwtTokens tokens = new BenchJwtTokens(System.getenv("PEERGRAB_AUTH_JWT_SECRET"), db);
        String publisherToken = tokens.issue(PUBLISHER_ID);
        String runId = recorder.startRun("BENCH", "S1", concurrency,
                "应用与中间件、发压端同机，存在资源争抢；数字仅作基线");
        List<Long> trackedErrands = new ArrayList<>();
        System.out.println("[run] runId=" + runId);

        // 串行预热应用/JIT 和少量可复用连接；并未预建 2,000 条并发连接，
        // 因此尖峰结果仍包含发压端的连接建立与调度成本。
        System.out.println("[warmup] 串行发布并抢取 30 个任务，预热发布与抢单路径...");
        warmup(client, baseUrl, trackedErrands, publisherToken, tokens);
        // Session fixture creation is setup work; exclude it from the timed spike.
        String[] runnerTokens = new String[concurrency];
        for (int i = 0; i < concurrency; i++) {
            runnerTokens[i] = tokens.issue(2001L + i);
        }

        long errandId = publish(client, baseUrl, slotTotal, publisherToken);
        trackedErrands.add(errandId);
        System.out.printf("[setup] 已发布任务 errandId=%d slotTotal=%d%n", errandId, slotTotal);

        AtomicInteger success = new AtomicInteger();
        AtomicInteger slotFull = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger rateLimited = new AtomicInteger();
        AtomicInteger error = new AtomicInteger();
        // 错误必须分类统计：不区分类型就无法判断瓶颈在发压端还是被压端
        java.util.Map<String, AtomicInteger> errorTypes = new java.util.concurrent.ConcurrentHashMap<>();
        // Each virtual thread writes only its own slot. Shared collection locks would
        // distort exactly the client dispatch and response phases being measured.
        long[] latencies = new long[concurrency];
        long[] dispatchLags = new long[concurrency];
        long[] releaseToComplete = new long[concurrency];
        Outcome[] outcomes = new Outcome[concurrency];
        Arrays.fill(latencies, -1);

        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch fire = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicLong releaseNanos = new AtomicLong();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < concurrency; i++) {
                int runnerIndex = i;
                String runnerToken = runnerTokens[i];
                pool.submit(() -> {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/errands/" + errandId + "/grab"))
                            .header("Authorization", "Bearer " + runnerToken)
                            .header("X-Request-Id", UUID.randomUUID().toString())
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build();
                    ready.countDown();
                    try {
                        fire.await();   // 同时放行，实际 send 开始时间另行统计
                        long releaseAt = releaseNanos.get();
                        long t0 = System.nanoTime();
                        HttpResponse<String> resp;
                        try {
                            resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                        } finally {
                            long finishedAt = System.nanoTime();
                            latencies[runnerIndex] = TimeUnit.NANOSECONDS.toMillis(finishedAt - t0);
                            dispatchLags[runnerIndex] = TimeUnit.NANOSECONDS.toMillis(t0 - releaseAt);
                            releaseToComplete[runnerIndex] = TimeUnit.NANOSECONDS.toMillis(finishedAt - releaseAt);
                        }
                        outcomes[runnerIndex] = classifyResponse(resp.statusCode(), resp.body());
                        switch (outcomes[runnerIndex]) {
                            case SUCCESS -> success.incrementAndGet();
                            case SLOT_FULL -> slotFull.incrementAndGet();
                            case GRAB_CONFLICT -> conflict.incrementAndGet();
                            case RATE_LIMITED -> rateLimited.incrementAndGet();
                            case ERROR -> error.incrementAndGet();
                        }
                    } catch (Exception e) {
                        outcomes[runnerIndex] = Outcome.ERROR;
                        error.incrementAndGet();
                        String type = e.getClass().getSimpleName()
                                + (e.getMessage() == null ? "" : ": " + e.getMessage());
                        errorTypes.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            if (!ready.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("线程未在 60s 内就绪");
            }
            long start = System.currentTimeMillis();
            releaseNanos.set(System.nanoTime());
            fire.countDown();
            if (!done.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("压测未在 180s 内完成");
            }
            long elapsed = System.currentTimeMillis() - start;

            List<Long> sorted = new ArrayList<>(concurrency);
            List<Long> sortedDispatch = new ArrayList<>(concurrency);
            List<Long> sortedFromRelease = new ArrayList<>(concurrency);
            EnumMap<Outcome, List<Long>> outcomeLatencies = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) outcomeLatencies.put(outcome, new ArrayList<>());
            for (int i = 0; i < concurrency; i++) {
                if (latencies[i] < 0) continue;
                sorted.add(latencies[i]);
                sortedDispatch.add(dispatchLags[i]);
                sortedFromRelease.add(releaseToComplete[i]);
                outcomeLatencies.get(outcomes[i]).add(latencies[i]);
            }
            Collections.sort(sorted);
            Collections.sort(sortedDispatch);
            Collections.sort(sortedFromRelease);
            System.out.println("================ S1 尖峰抢单结果 ================");
            System.out.printf("并发数        : %d%n", concurrency);
            System.out.printf("名额总数      : %d%n", slotTotal);
            System.out.printf("成功          : %d  <- 必须等于名额数%n", success.get());
            System.out.printf("名额已满      : %d%n", slotFull.get());
            System.out.printf("并发冲突      : %d%n", conflict.get());
            System.out.printf("热点限流      : %d%n", rateLimited.get());
            System.out.printf("错误          : %d%n", error.get());
            if (!errorTypes.isEmpty()) {
                System.out.println("错误分类      :");
                errorTypes.entrySet().stream()
                        .sorted((a, b) -> b.getValue().get() - a.getValue().get())
                        .limit(5)
                        .forEach(en -> System.out.printf("                %4d x %s%n", en.getValue().get(), en.getKey()));
            }
            System.out.printf("总耗时        : %d ms%n", elapsed);
            System.out.printf("吞吐(参考)    : %.0f req/s%n", concurrency * 1000.0 / Math.max(elapsed, 1));
            if (!sorted.isEmpty()) {
                System.out.printf("延迟 P50/P95/P99/Max : %d / %d / %d / %d ms%n",
                        pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), sorted.get(sorted.size() - 1));
            }
            if (!sortedDispatch.isEmpty()) {
                System.out.printf("放行到 send P50/P99/Max : %d / %d / %d ms%n",
                        pct(sortedDispatch, 50), pct(sortedDispatch, 99),
                        sortedDispatch.get(sortedDispatch.size() - 1));
                System.out.printf("放行到完成 P50/P99/Max : %d / %d / %d ms%n",
                        pct(sortedFromRelease, 50), pct(sortedFromRelease, 99),
                        sortedFromRelease.get(sortedFromRelease.size() - 1));
            }
            for (Outcome outcome : Outcome.values()) {
                List<Long> subset = new ArrayList<>(outcomeLatencies.get(outcome));
                if (subset.isEmpty()) continue;
                Collections.sort(subset);
                System.out.printf("%-16s n=%d P50/P95/P99=%d/%d/%d ms%n", outcome,
                        subset.size(), pct(subset, 50), pct(subset, 95), pct(subset, 99));
            }
            System.out.println("errandId=" + errandId);
            System.out.println("runId=" + runId + "（校验：RUN_ID=" + runId + " bench/scripts/verify_run.sql）");
            System.out.println("=================================================");

            boolean pass = success.get() == slotTotal && error.get() == 0;
            long p99 = sorted.isEmpty() ? -1 : pct(sorted, 99);
            long throughput = Math.round(concurrency * 1000.0 / Math.max(elapsed, 1));
            String summary = String.format(
                    "{\"success\":%d,\"slotTotal\":%d,\"slotFull\":%d,\"conflict\":%d,\"errors\":%d,"
                            + "\"rateLimited\":%d,\"elapsedMs\":%d,\"throughput\":%d,\"p99Ms\":%d,\"oversold\":%d}",
                    success.get(), slotTotal, slotFull.get(), conflict.get(), error.get(),
                    rateLimited.get(),
                    elapsed, throughput, p99, Math.max(0, success.get() - slotTotal));
            recorder.trackErrands(runId, trackedErrands);
            recorder.finishRun(runId, pass ? "PASS" : "FAIL", summary);

            if (!pass) {
                System.err.printf("[FAIL] 成功数 %d != 名额数 %d，发生超卖或少卖%n", success.get(), slotTotal);
                System.exit(1);
            }
            System.out.println("[PASS] 应用层零超卖，请继续用 verify_run.sql 做数据库侧交叉校验");
        }
        }
    }

    enum Outcome {
        SUCCESS,
        SLOT_FULL,
        GRAB_CONFLICT,
        RATE_LIMITED,
        ERROR
    }

    static Outcome classifyResponse(int statusCode, String body) {
        if (statusCode == 200 && body.contains("\"code\":\"OK\"")) {
            return Outcome.SUCCESS;
        }
        if (body.contains("SLOT_FULL")) {
            return Outcome.SLOT_FULL;
        }
        if (body.contains("GRAB_CONFLICT")) {
            return Outcome.GRAB_CONFLICT;
        }
        if (body.contains("GRAB_RATE_LIMITED")) {
            return Outcome.RATE_LIMITED;
        }
        return Outcome.ERROR;
    }

    private static long pct(List<Long> sorted, int p) {
        int idx = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static void warmup(HttpClient client, String baseUrl, List<Long> tracked,
                               String publisherToken, BenchJwtTokens tokens) throws Exception {
        for (int i = 0; i < 30; i++) {
            long id = publish(client, baseUrl, 1, publisherToken);
            tracked.add(id);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/errands/" + id + "/grab"))
                    .header("Authorization", "Bearer " + tokens.issue(9_001L + i))
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (classifyResponse(response.statusCode(), response.body()) != Outcome.SUCCESS) {
                throw new IllegalStateException("S1 warmup grab failed: " + response.statusCode());
            }
        }
    }

    private static long publish(HttpClient client, String baseUrl, int slotTotal, String publisherToken) throws Exception {
        String json = """
                {"campusId":1,"type":"DELIVERY","title":"bench_尖峰抢单任务","rewardCents":100,"slotTotal":%d}
                """.formatted(slotTotal);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/errands"))
                .header("Authorization", "Bearer " + publisherToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();
        return parseErrandId(body);
    }

    static long parseErrandId(String body) {
        int idx = body.indexOf("\"errandId\":");
        if (idx < 0) {
            throw new IllegalStateException("发布任务失败: " + body);
        }
        int start = idx + 11;
        boolean quoted = start < body.length() && body.charAt(start) == '"';
        if (quoted) {
            start++;
        }
        int end = start;
        while (end < body.length() && (Character.isDigit(body.charAt(end)) || body.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            throw new IllegalStateException("发布任务响应缺少 errandId 数值: " + body);
        }
        return Long.parseLong(body.substring(start, end));
    }
}
