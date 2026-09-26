package com.peergrab.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-arrival-rate list benchmark. Scheduled offers never wait for responses.
 *
 * Usage: OpenLoopLoadClient <baseUrl> <rpsCsv> <warmupSeconds> <sampleSeconds>
 *                           <maxInFlight> [requestTimeoutMillis]
 *
 * Authentication: PEERGRAB_BENCH_TOKEN, or PEERGRAB_AUTH_DEMO_PASSWORD_1001 for demo login.
 * An isolated benchmark stack is required by BenchSafety.
 */
public final class OpenLoopLoadClient {

    private static final int MAX_STAGES = 10;
    private static final int MAX_RPS = 50_000;
    private static final int MAX_SECONDS = 600;
    private static final int MAX_IN_FLIGHT = 2_000;
    private static final int MAX_OFFERS_PER_PHASE = 2_000_000;
    private static final String PATH = "/api/errands?campusId=1&status=PUBLISHED&size=20";

    record Config(String baseUrl, int[] rates, int warmupSeconds, int sampleSeconds,
                  int maxInFlight, int timeoutMillis) {}

    record PhaseResult(int offered, int sent, int completed, int completedWithinWindow,
                       int ok, int businessErrors, int exceptionErrors,
                       int capacityRejected, int schedulerMissed, int serverRejected,
                       int peakInFlight, int tailInFlight, Map<Integer, Integer> httpErrors,
                       Map<String, Integer> exceptionTypes, Map<String, Integer> businessCodes,
                       long[] sortedLatencyMicros) {
        int localRejected() { return capacityRejected + schedulerMissed; }
        int httpErrorCount() { return httpErrors.values().stream().mapToInt(Integer::intValue).sum(); }
        long percentile(int percent) {
            if (sortedLatencyMicros.length == 0) return -1;
            int index = (int) Math.ceil(percent * sortedLatencyMicros.length / 100.0) - 1;
            return sortedLatencyMicros[Math.max(0, Math.min(index, sortedLatencyMicros.length - 1))];
        }
        long maxLatencyMicros() {
            return sortedLatencyMicros.length == 0 ? -1
                    : sortedLatencyMicros[sortedLatencyMicros.length - 1];
        }
    }

    private OpenLoopLoadClient() {}

    public static void main(String[] args) throws Exception {
        Config config = parseArguments(args);
        BenchSafety.requireDisposableStack(config.baseUrl());

        try (ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(config.maxInFlight(), 64))) {
            HttpClient client = HttpClient.newBuilder()
                    .executor(executor)
                    .connectTimeout(Duration.ofMillis(Math.min(config.timeoutMillis(), 5_000)))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();
            String token = resolveToken(client, config);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + PATH))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofMillis(config.timeoutMillis()))
                    .GET()
                    .build();
            for (int rate : config.rates()) {
                runStage(client, request, config, rate);
            }
        }
    }

    static Config parseArguments(String[] args) {
        if (args.length < 5 || args.length > 6) {
            throw new IllegalArgumentException("Usage: OpenLoopLoadClient <baseUrl> <rpsCsv> "
                    + "<warmupSeconds> <sampleSeconds> <maxInFlight> [requestTimeoutMillis]");
        }
        URI uri;
        try {
            uri = URI.create(args[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL", e);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || uri.getScheme() == null
                || (!uri.getScheme().equalsIgnoreCase("http")
                    && !uri.getScheme().equalsIgnoreCase("https"))
                || args[0].length() > 100) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL without credentials/query");
        }
        String base = args[0].replaceAll("/+$", "");
        String[] parts = args[1].split(",", -1);
        if (parts.length < 1 || parts.length > MAX_STAGES) {
            throw new IllegalArgumentException("rpsCsv must contain 1.." + MAX_STAGES + " stages");
        }
        LinkedHashSet<Integer> uniqueRates = new LinkedHashSet<>();
        for (String part : parts) {
            int rate = boundedInt(part.trim(), "offered RPS", 1, MAX_RPS);
            if (!uniqueRates.add(rate)) {
                throw new IllegalArgumentException("duplicate offered RPS stage: " + rate);
            }
        }
        int warmup = boundedInt(args[2], "warmupSeconds", 1, MAX_SECONDS);
        int sample = boundedInt(args[3], "sampleSeconds", 1, MAX_SECONDS);
        int maxInFlight = boundedInt(args[4], "maxInFlight", 1, MAX_IN_FLIGHT);
        int timeout = args.length == 6
                ? boundedInt(args[5], "requestTimeoutMillis", 100, 30_000) : 5_000;
        for (int rate : uniqueRates) {
            if ((long) rate * Math.max(warmup, sample) > MAX_OFFERS_PER_PHASE) {
                throw new IllegalArgumentException("each phase is limited to "
                        + MAX_OFFERS_PER_PHASE + " offered requests");
            }
        }
        return new Config(base, uniqueRates.stream().mapToInt(Integer::intValue).toArray(),
                warmup, sample, maxInFlight, timeout);
    }

    private static int boundedInt(String input, String name, int min, int max) {
        try {
            int value = Integer.parseInt(input);
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException ignored) {
            // Use the same validation message for malformed and out-of-range values.
        }
        throw new IllegalArgumentException(name + " must be in " + min + ".." + max);
    }

    private static String resolveToken(HttpClient client, Config config) throws Exception {
        String token = System.getenv("PEERGRAB_BENCH_TOKEN");
        if (token != null && !token.isBlank()) {
            token = token.trim();
            return token.startsWith("Bearer ") ? token.substring(7).trim() : token;
        }
        String password = System.getenv("PEERGRAB_AUTH_DEMO_PASSWORD_1001");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Set PEERGRAB_BENCH_TOKEN or PEERGRAB_AUTH_DEMO_PASSWORD_1001");
        }
        HttpRequest login = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(config.timeoutMillis()))
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"userId\":1001,\"password\":\"" + jsonEscape(password) + "\"}"))
                .build();
        HttpResponse<String> response = client.send(login, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !"OK".equals(jsonStringField(response.body(), "code"))) {
            throw new IllegalStateException("Demo login rejected: HTTP " + response.statusCode());
        }
        String issued = jsonStringField(response.body(), "token");
        if (issued == null || issued.isBlank()) {
            throw new IllegalStateException("Demo login response lacks token");
        }
        return issued;
    }

    private static void runStage(HttpClient client, HttpRequest request, Config config, int rate)
            throws Exception {
        try (BenchRunRecorder recorder = new BenchRunRecorder()) {
            String note = String.format(Locale.ROOT,
                    "open-loop GET %s; offered=%d/s; warmup=%ds; sample=%ds; maxInFlight=%d; timeout=%dms",
                    config.baseUrl() + PATH, rate, config.warmupSeconds(), config.sampleSeconds(),
                    config.maxInFlight(), config.timeoutMillis());
            String runId = recorder.startRun("BENCH", "S2-OPEN", config.maxInFlight(),
                    note.length() <= 255 ? note : note.substring(0, 255));
            try {
                PhaseResult warmup = runPhase(client, request, rate, config.warmupSeconds(),
                        config.maxInFlight(), config.timeoutMillis(), false);
                PhaseResult sample = runPhase(client, request, rate, config.sampleSeconds(),
                        config.maxInFlight(), config.timeoutMillis(), true);
                boolean pass = sample.ok() == sample.offered()
                        && sample.localRejected() == 0 && sample.exceptionErrors() == 0
                        && sample.httpErrorCount() == 0 && sample.businessErrors() == 0;
                recorder.finishRun(runId, pass ? "PASS" : "FAIL",
                        summaryJson(config, rate, warmup, sample));
                printStage(runId, rate, config.sampleSeconds(), warmup, sample);
            } catch (Exception e) {
                recorder.finishRun(runId, "FAIL", "{\"error\":\"" + jsonEscape(e.toString()) + "\"}");
                throw e;
            }
        }
    }

    /**
     * Exact absolute offer times. A late scheduler offer is dropped, never sent as a
     * catch-up burst. Capacity rejection is recorded separately from server rejection.
     */
    static PhaseResult runPhase(HttpClient client, HttpRequest request, int rate,
                                int seconds, int maxInFlight, int timeoutMillis,
                                boolean captureLatencies) throws InterruptedException {
        int planned = Math.multiplyExact(rate, seconds);
        PhaseCounter counter = new PhaseCounter(captureLatencies ? planned : 0);
        Semaphore permits = new Semaphore(maxInFlight);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        long started = System.nanoTime();
        long windowEnd = started + TimeUnit.SECONDS.toNanos(seconds);
        long interval = TimeUnit.SECONDS.toNanos(1) / rate;
        long allowedLag = Math.max(1_000_000L, Math.min(100_000_000L, interval * 2));
        for (int i = 0; i < planned; i++) {
            long target = started + (long) i * TimeUnit.SECONDS.toNanos(1) / rate;
            parkUntil(target);
            counter.offered++;
            if (System.nanoTime() - target > allowedLag) {
                counter.schedulerMissed++;
                continue;
            }
            if (!permits.tryAcquire()) {
                counter.capacityRejected++;
                continue;
            }
            int active = inFlight.incrementAndGet();
            peak.accumulateAndGet(active, Math::max);
            counter.sent++;
            long requestStart = System.nanoTime();
            try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                        .whenComplete((response, error) -> {
                            try {
                                counter.record(response, error, System.nanoTime() - requestStart);
                            } finally {
                                inFlight.decrementAndGet();
                                permits.release();
                            }
                        });
            } catch (RuntimeException e) {
                try {
                    counter.record(null, e, System.nanoTime() - requestStart);
                } finally {
                    inFlight.decrementAndGet();
                    permits.release();
                }
            }
        }
        parkUntil(windowEnd);
        int tail = inFlight.get();
        int completedWithinWindow = counter.completed.get();
        if (!permits.tryAcquire(maxInFlight, timeoutMillis + 10_000L, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("requests did not drain within timeout; outstanding="
                    + inFlight.get());
        }
        permits.release(maxInFlight);
        if (counter.sent != counter.completed.get()) {
            throw new IllegalStateException("completed requests differ from sends");
        }
        return counter.snapshot(completedWithinWindow, peak.get(), tail);
    }

    private static void parkUntil(long target) throws InterruptedException {
        while (true) {
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("benchmark interrupted");
            }
            long remaining = target - System.nanoTime();
            if (remaining <= 0) return;
            LockSupport.parkNanos(remaining);
        }
    }

    private static final class PhaseCounter {
        int offered;
        int sent;
        int capacityRejected;
        int schedulerMissed;
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger ok = new AtomicInteger();
        final AtomicInteger businessErrors = new AtomicInteger();
        final AtomicInteger exceptionErrors = new AtomicInteger();
        final AtomicInteger serverRejected = new AtomicInteger();
        final ConcurrentHashMap<Integer, AtomicInteger> httpErrors = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicInteger> exceptionTypes = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, AtomicInteger> businessCodes = new ConcurrentHashMap<>();
        final AtomicInteger latencyIndex = new AtomicInteger();
        final long[] latencyMicros;

        PhaseCounter(int latencyCapacity) {
            latencyMicros = new long[latencyCapacity];
        }

        void record(HttpResponse<String> response, Throwable error, long elapsedNanos) {
            if (latencyMicros.length != 0) {
                latencyMicros[latencyIndex.getAndIncrement()] =
                        Math.max(0, TimeUnit.NANOSECONDS.toMicros(elapsedNanos));
            }
            if (error != null || response == null) {
                exceptionErrors.incrementAndGet();
                Throwable cause = error;
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                String kind = cause == null ? "NoResponse" : cause.getClass().getSimpleName();
                exceptionTypes.computeIfAbsent(kind, ignored -> new AtomicInteger()).incrementAndGet();
            } else if (response.statusCode() != 200) {
                httpErrors.computeIfAbsent(response.statusCode(), ignored -> new AtomicInteger())
                        .incrementAndGet();
                if (response.statusCode() == 429 || response.statusCode() == 503) {
                    serverRejected.incrementAndGet();
                }
            } else {
                String code = jsonStringField(response.body(), "code");
                if ("OK".equals(code)) {
                    ok.incrementAndGet();
                } else {
                    businessErrors.incrementAndGet();
                    businessCodes.computeIfAbsent(code == null ? "MISSING_CODE" : code,
                            ignored -> new AtomicInteger()).incrementAndGet();
                }
            }
            completed.incrementAndGet();
        }

        PhaseResult snapshot(int completedWithinWindow, int peak, int tail) {
            long[] sorted = Arrays.copyOf(latencyMicros, latencyIndex.get());
            Arrays.sort(sorted);
            return new PhaseResult(offered, sent, completed.get(), completedWithinWindow,
                    ok.get(), businessErrors.get(), exceptionErrors.get(),
                    capacityRejected, schedulerMissed, serverRejected.get(), peak, tail,
                    snapshotMap(httpErrors), snapshotMap(exceptionTypes),
                    snapshotMap(businessCodes), sorted);
        }
    }

    private static <K> Map<K, Integer> snapshotMap(ConcurrentHashMap<K, AtomicInteger> counters) {
        Map<K, Integer> result = new ConcurrentHashMap<>();
        counters.forEach((key, value) -> result.put(key, value.get()));
        return Map.copyOf(result);
    }

    private static void printStage(String runId, int rate, int seconds,
                                   PhaseResult warmup, PhaseResult result) {
        System.out.printf(Locale.ROOT,
                "runId=%s offeredRps=%d sample=%ds offered=%d sent=%d completed=%d "
                        + "completedWithinWindow=%d ok=%d httpErrors=%d appErrors=%d "
                        + "exceptions=%d localRejected=%d(capacity=%d,scheduler=%d) "
                        + "serverRejected=%d peakInFlight=%d tailInFlight=%d%n",
                runId, rate, seconds, result.offered(), result.sent(), result.completed(),
                result.completedWithinWindow(), result.ok(), result.httpErrorCount(),
                result.businessErrors(), result.exceptionErrors(), result.localRejected(),
                result.capacityRejected(), result.schedulerMissed(), result.serverRejected(),
                result.peakInFlight(), result.tailInFlight());
        System.out.printf(Locale.ROOT,
                "latency all sent incl failures P50/P95/P99/Max=%.3f/%.3f/%.3f/%.3f ms; "
                        + "windowCompletedRps=%.1f; warmup offered=%d rejected=%d%n",
                millis(result.percentile(50)), millis(result.percentile(95)),
                millis(result.percentile(99)), millis(result.maxLatencyMicros()),
                result.completedWithinWindow() / (double) seconds,
                warmup.offered(), warmup.localRejected());
        if (!result.httpErrors().isEmpty() || !result.exceptionTypes().isEmpty()
                || !result.businessCodes().isEmpty()) {
            System.out.println("errors http=" + result.httpErrors()
                    + " exceptions=" + result.exceptionTypes()
                    + " business=" + result.businessCodes());
        }
    }

    private static double millis(long micros) {
        return micros < 0 ? -1 : micros / 1_000.0;
    }

    private static String summaryJson(Config config, int rate, PhaseResult warmup,
                                      PhaseResult result) {
        return String.format(Locale.ROOT,
                "{\"path\":\"%s\",\"offeredRps\":%d,\"warmupSeconds\":%d,\"sampleSeconds\":%d,"
                        + "\"maxInFlightLimit\":%d,\"timeoutMillis\":%d,"
                        + "\"warmupOffered\":%d,\"warmupRejected\":%d,"
                        + "\"offered\":%d,\"sent\":%d,\"completed\":%d,\"completedWithinWindow\":%d,"
                        + "\"ok\":%d,\"httpErrors\":%s,\"businessErrors\":%d,\"businessCodes\":%s,"
                        + "\"exceptionErrors\":%d,\"exceptionTypes\":%s,"
                        + "\"capacityRejected\":%d,\"schedulerMissed\":%d,\"localRejected\":%d,"
                        + "\"serverRejected\":%d,\"peakInFlight\":%d,\"tailInFlight\":%d,"
                        + "\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"p99Ms\":%.3f,\"maxMs\":%.3f}",
                PATH, rate, config.warmupSeconds(), config.sampleSeconds(),
                config.maxInFlight(), config.timeoutMillis(),
                warmup.offered(), warmup.localRejected(),
                result.offered(), result.sent(), result.completed(), result.completedWithinWindow(),
                result.ok(), jsonIntMap(result.httpErrors()), result.businessErrors(),
                jsonStringMap(result.businessCodes()), result.exceptionErrors(),
                jsonStringMap(result.exceptionTypes()), result.capacityRejected(),
                result.schedulerMissed(), result.localRejected(), result.serverRejected(),
                result.peakInFlight(), result.tailInFlight(),
                millis(result.percentile(50)), millis(result.percentile(95)),
                millis(result.percentile(99)), millis(result.maxLatencyMicros()));
    }

    private static String jsonIntMap(Map<Integer, Integer> map) {
        Map<String, Integer> converted = new TreeMap<>();
        map.forEach((key, value) -> converted.put(Integer.toString(key), value));
        return jsonStringMap(converted);
    }

    private static String jsonStringMap(Map<String, Integer> map) {
        List<String> entries = new ArrayList<>();
        new TreeMap<>(map).forEach((key, value) ->
                entries.add("\"" + jsonEscape(key) + "\":" + value));
        return "{" + String.join(",", entries) + "}";
    }

    private static String jsonStringField(String json, String name) {
        if (json == null) return null;
        int key = json.indexOf("\"" + name + "\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key + name.length() + 2);
        if (colon < 0) return null;
        int quote = colon + 1;
        while (quote < json.length() && Character.isWhitespace(json.charAt(quote))) quote++;
        if (quote >= json.length() || json.charAt(quote) != '"') return null;
        int end = json.indexOf('"', quote + 1);
        return end < 0 ? null : json.substring(quote + 1, end);
    }

    private static String jsonEscape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
