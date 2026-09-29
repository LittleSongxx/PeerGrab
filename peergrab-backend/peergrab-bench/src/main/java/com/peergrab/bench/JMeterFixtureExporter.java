package com.peergrab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Guarded fixture exporter for JMeter HTTP plans. Authentication/session creation
 * deliberately reuses BenchJwtTokens, including its disposable DB marker check.
 * All setup requests happen before the timed JMeter phase.
 */
public final class JMeterFixtureExporter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long S3_BASE = 920_000_000_000L;
    private static final int S3_SEED_COUNT = 100;
    private static final long S3_RANDOM_SEED = 20260928L;
    private static final int REWARD_CENTS = 100;
    private static final long S4_DISTRIBUTED_RUNNER_BASE = 60_000L;

    private JMeterFixtureExporter() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) throw new IllegalArgumentException(usage());
        String scenario = args[0];
        String baseUrl = args[1].replaceAll("/+$", "");
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        BenchSafety.requireDisposableStack(baseUrl);
        if (Files.exists(output)) throw new IllegalArgumentException("Fixture output directory already exists: " + output);
        if (Files.isSymbolicLink(output.getParent())) throw new IllegalArgumentException("Fixture parent must not be a symlink");
        String password = requiredEnv("PEERGRAB_TEST_DB_PASSWORD");
        String jdbc = "jdbc:mysql://" + requiredEnv("PEERGRAB_TEST_DB_HOST") + ":"
                + requiredEnv("PEERGRAB_TEST_DB_PORT")
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        String dbUser = System.getenv().getOrDefault("PEERGRAB_TEST_DB_USER", "root");
        Files.createDirectories(output);
        Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        try (Connection db = DriverManager.getConnection(jdbc, dbUser, password);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbc, dbUser, password)) {
            BenchJwtTokens tokens = new BenchJwtTokens(requiredEnv("PEERGRAB_AUTH_JWT_SECRET"), db);
            Fixture fixture = switch (scenario) {
                case "s1" -> s1(args, baseUrl, http, tokens, recorder);
                case "s1-distinct" -> s1Distinct(args, baseUrl, db, http, tokens, recorder);
                case "s3-read" -> s3Read(args, baseUrl, db, http, tokens, recorder);
                case "s3-mixed" -> s3Mixed(args, baseUrl, http, tokens, recorder);
                case "s4" -> s4(args, baseUrl, db, http, tokens, recorder);
                default -> throw new IllegalArgumentException(usage());
            };
            try {
                for (Map.Entry<String, List<String>> csv : fixture.csv().entrySet()) {
                    Path file = output.resolve(csv.getKey());
                    Files.write(file, csv.getValue(), StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                            java.nio.file.StandardOpenOption.WRITE);
                    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
                }
                Map<String, Object> manifest = new LinkedHashMap<>(fixture.metadata());
                manifest.put("runId", fixture.runId());
                manifest.put("project", requiredEnv("PEERGRAB_BENCH_PROJECT"));
                manifest.put("scenario", scenario);
                manifest.put("baseUrl", baseUrl);
                Path manifestPath = output.resolve("manifest.json");
                Files.writeString(manifestPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest),
                        StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
                Files.setPosixFilePermissions(manifestPath, PosixFilePermissions.fromString("rw-------"));
                System.out.println("JMeter fixture ready: " + manifestPath + " runId=" + fixture.runId());
            } catch (Exception e) {
                recorder.finishRun(fixture.runId(), "FAIL", "{\"reason\":\"fixture_export_failed\"}");
                throw e;
            }
        }
    }

    private record Fixture(String runId, Map<String, List<String>> csv, Map<String, Object> metadata) { }

    private static Fixture s1(String[] args, String base, HttpClient http,
                              BenchJwtTokens tokens, BenchRunRecorder recorder) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(usage());
        int users = bounded(args[3], 1, 2500, "S1 users");
        String runId = recorder.startRun("BENCH", "JM-S1", users,
                "JMeter one-task spike; JWT and publish excluded from timed phase");
        try {
            String publisher = tokens.issue(1001);
            // Warm the application path before timing, matching SpikeLoadClient's
            // 30 serial publish/grab fixtures. JMeter's own connections are not pre-opened.
            for (int i = 0; i < 30; i++) {
                long warmId = publish(http, base, publisher, runId, "s1_warm_" + i, 1);
                recorder.trackErrand(runId, warmId);
                post(http, base, "/api/errands/" + warmId + "/grab",
                        tokens.issue(9_001L + i), "{}", UUID.randomUUID().toString());
            }
            List<String> lines = new ArrayList<>(users + 1);
            lines.add("token,request_id");
            for (int i = 0; i < users; i++) {
                lines.add(tokens.issue(2001L + i) + "," + UUID.randomUUID());
            }
            long id = publish(http, base, publisher, runId, "s1", 1);
            recorder.trackErrand(runId, id);
            return new Fixture(runId, Map.of("s1.csv", lines),
                    Map.of("users", users, "errandId", id, "slotTotal", 1,
                            "applicationWarmupTasks", 30));
        } catch (Exception e) {
            recorder.finishRun(runId, "FAIL", "{\"reason\":\"fixture_setup_failed\"}");
            throw e;
        }
    }

    private static Fixture s1Distinct(String[] args, String base, Connection db, HttpClient http,
                                      BenchJwtTokens tokens, BenchRunRecorder recorder) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(usage());
        int tasks = bounded(args[3], 1, 900, "S1 distinct tasks");
        int threads = bounded(args[4], 1, 128, "S1 distinct threads");
        try (PreparedStatement balance = db.prepareStatement(
                "SELECT available FROM wallet_account WHERE owner_type='USER' AND owner_id=1001");
             ResultSet rows = balance.executeQuery()) {
            if (!rows.next() || rows.getLong(1) < (long) tasks * REWARD_CENTS) {
                throw new IllegalStateException("S1 distinct publisher balance too low");
            }
        }
        String runId = recorder.startRun("BENCH", "JM-S1-DISTINCT", threads,
                "Independent task/runner grab batch; setup excluded from timed phase");
        try {
            String publisher = tokens.issue(1001);
            List<String> csv = new ArrayList<>(tasks + 1);
            csv.add("errand_id,token,request_id");
            for (int i = 0; i < tasks; i++) {
                long id = publish(http, base, publisher, runId, "distinct_grab_" + i, 1);
                recorder.trackErrand(runId, id);
                csv.add(id + "," + tokens.issue(2001L + i) + "," + UUID.randomUUID());
            }
            return new Fixture(runId, Map.of("s1_distinct.csv", csv),
                    Map.of("tasks", tasks, "threads", threads));
        } catch (Exception e) {
            recorder.finishRun(runId, "FAIL", "{\"reason\":\"fixture_setup_failed\"}");
            throw e;
        }
    }

    private static Fixture s3Read(String[] args, String base, Connection db, HttpClient http,
                                  BenchJwtTokens tokens, BenchRunRecorder recorder) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(usage());
        int threads = bounded(args[3], 1, 200, "S3 threads");
        int iterations = bounded(args[4], 1, 1000, "S3 iterations");
        if ((long) threads * iterations > 100_000) throw new IllegalArgumentException("S3 read cap is 100000");
        String distribution = args[5];
        if (!List.of("uniform", "hot90").contains(distribution)) throw new IllegalArgumentException("S3 distribution must be uniform or hot90");
        try (PreparedStatement statement = db.prepareStatement("""
                SELECT COUNT(*) FROM errand WHERE id > ? AND id <= ? AND status='PUBLISHED' AND campus_id=1
                """)) {
            statement.setLong(1, S3_BASE);
            statement.setLong(2, S3_BASE + S3_SEED_COUNT);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != S3_SEED_COUNT) {
                    throw new IllegalStateException("S3 requires seed_bench.py s3 and Bloom rebuild on this disposable stack");
                }
            }
        }
        String runId = recorder.startRun("BENCH", "JM-S3-READ", threads,
                "JMeter detail read; " + distribution + "; seed=" + S3_RANDOM_SEED);
        try {
            String token = tokens.issue(1001);
            String admin = tokens.issue(9001);
            post(http, base, "/api/internal/cache-stats/reset", admin, "{}", null);
            int total = threads * iterations;
            List<Long> ids = new ArrayList<>(total);
            SplittableRandom random = new SplittableRandom(S3_RANDOM_SEED);
            for (int i = 0; i < total; i++) {
                int chosen = distribution.equals("hot90") && i < total * 9 / 10
                        ? 1 : (distribution.equals("hot90") ? 2 + random.nextInt(99) : 1 + random.nextInt(100));
                ids.add(S3_BASE + chosen);
            }
            // Keep the exact hot-key fraction while spreading hot and cold accesses through the run.
            Collections.shuffle(ids, new java.util.Random(S3_RANDOM_SEED));
            List<String> lines = new ArrayList<>(total + 1);
            lines.add("errand_id,token");
            for (long id : ids) lines.add(id + "," + token);
            List<Long> tracked = new ArrayList<>(S3_SEED_COUNT);
            for (int i = 1; i <= S3_SEED_COUNT; i++) tracked.add(S3_BASE + i);
            recorder.trackErrands(runId, tracked);
            return new Fixture(runId, Map.of("s3_read.csv", lines, "s3_admin.csv", List.of("token", admin)),
                    Map.of("threads", threads, "iterations", iterations, "requests", total,
                            "distribution", distribution, "seed", S3_RANDOM_SEED));
        } catch (Exception e) {
            recorder.finishRun(runId, "FAIL", "{\"reason\":\"fixture_setup_failed\"}");
            throw e;
        }
    }

    private static Fixture s3Mixed(String[] args, String base, HttpClient http, BenchJwtTokens tokens,
                                   BenchRunRecorder recorder) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException(usage());
        int threads = bounded(args[3], 1, 100, "S3 mixed threads");
        int iterations = bounded(args[4], 1, 50, "S3 mixed iterations");
        if ((long) threads * iterations > 500) throw new IllegalArgumentException("S3 mixed write cap is 500");
        String runId = recorder.startRun("BENCH", "JM-S3-MIXED", threads,
                "JMeter publish then nine reads; 10 HTTP operations per iteration");
        try {
            String token = tokens.issue(1001);
            String admin = tokens.issue(9001);
            post(http, base, "/api/internal/cache-stats/reset", admin, "{}", null);
            List<String> lines = new ArrayList<>(threads * iterations + 1);
            lines.add("token,request_id");
            for (int i = 0; i < threads * iterations; i++) lines.add(token + "," + UUID.randomUUID());
            return new Fixture(runId, Map.of("s3_mixed.csv", lines, "s3_admin.csv", List.of("token", admin)),
                    Map.of("threads", threads, "iterations", iterations,
                            "writes", threads * iterations, "reads", threads * iterations * 9));
        } catch (Exception e) {
            recorder.finishRun(runId, "FAIL", "{\"reason\":\"fixture_setup_failed\"}");
            throw e;
        }
    }

    private static Fixture s4(String[] args, String base, Connection db, HttpClient http,
                              BenchJwtTokens tokens, BenchRunRecorder recorder) throws Exception {
        if (args.length != 6 && args.length != 7) throw new IllegalArgumentException(usage());
        int count = bounded(args[3], 1, 500, "S4 distinct tasks");
        int threads = bounded(args[4], 1, 64, "S4 threads");
        int duplicates = bounded(args[5], 2, 200, "S4 same-task attempts");
        String runnerWallets = args.length == 7 ? args[6] : "shared";
        if (!List.of("shared", "distributed").contains(runnerWallets)) {
            throw new IllegalArgumentException("S4 runner wallets must be shared or distributed");
        }
        try (PreparedStatement statement = db.prepareStatement(
                "SELECT available+frozen FROM wallet_account WHERE owner_type='USER' AND owner_id=1001")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) < (count + 1L) * REWARD_CENTS) {
                    throw new IllegalStateException("Publisher wallet balance too low for S4 fixtures");
                }
            }
        }
        String runId = recorder.startRun("BENCH", "JM-S4", threads,
                "JMeter distinct and same-task settle; runner wallets=" + runnerWallets
                        + "; lifecycle setup excluded from timed phase");
        try {
            if (runnerWallets.equals("distributed")) {
                seedS4RunnerWallets(db, count + 1);
            }
            long walletTotalBefore;
            try (PreparedStatement statement = db.prepareStatement(
                    "SELECT COALESCE(SUM(available+frozen),0) FROM wallet_account");
                 ResultSet rows = statement.executeQuery()) {
                rows.next();
                walletTotalBefore = rows.getLong(1);
            }
            String publisher = tokens.issue(1001);
            String sharedRunner = tokens.issue(2001);
            List<String> distinct = new ArrayList<>(count + 1);
            distinct.add("errand_id,token");
            List<Long> tracked = new ArrayList<>(count + 1);
            for (int i = 0; i < count; i++) {
                String runner = runnerWallets.equals("distributed")
                        ? tokens.issue(S4_DISTRIBUTED_RUNNER_BASE + i) : sharedRunner;
                long id = prepareDelivered(http, base, publisher, runner, runId, "distinct_" + i);
                recorder.trackErrand(runId, id);
                tracked.add(id);
                distinct.add(id + "," + publisher);
            }
            String sameRunner = runnerWallets.equals("distributed")
                    ? tokens.issue(S4_DISTRIBUTED_RUNNER_BASE + count) : sharedRunner;
            long sameId = prepareDelivered(http, base, publisher, sameRunner, runId, "same");
            recorder.trackErrand(runId, sameId);
            tracked.add(sameId);
            List<String> same = new ArrayList<>(duplicates + 1);
            same.add("errand_id,token");
            for (int i = 0; i < duplicates; i++) same.add(sameId + "," + publisher);
            return new Fixture(runId, Map.of("s4_distinct.csv", distinct, "s4_same.csv", same),
                    Map.of("tasks", count, "threads", threads, "sameTaskAttempts", duplicates,
                            "sameTaskId", sameId, "allErrandIds", tracked,
                            "walletTotalBefore", walletTotalBefore, "runnerWallets", runnerWallets));
        } catch (Exception e) {
            recorder.finishRun(runId, "FAIL", "{\"reason\":\"fixture_setup_failed\"}");
            throw e;
        }
    }

    private static void seedS4RunnerWallets(Connection db, int users) throws Exception {
        try (PreparedStatement check = db.prepareStatement("""
                SELECT COUNT(*) FROM wallet_account WHERE owner_type='USER'
                  AND owner_id >= ? AND owner_id < ?
                """)) {
            check.setLong(1, S4_DISTRIBUTED_RUNNER_BASE);
            check.setLong(2, S4_DISTRIBUTED_RUNNER_BASE + users);
            try (ResultSet rows = check.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new IllegalStateException("Distributed runner wallets already exist; use a fresh stack");
                }
            }
        }
        try (PreparedStatement insert = db.prepareStatement("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', 0, 0, 0)
                """)) {
            for (int i = 0; i < users; i++) {
                insert.setLong(1, S4_DISTRIBUTED_RUNNER_BASE + i);
                insert.setLong(2, S4_DISTRIBUTED_RUNNER_BASE + i);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private static long prepareDelivered(HttpClient http, String base, String publisher,
                                         String runner, String runId, String label) throws Exception {
        long id = publish(http, base, publisher, runId, label, 1);
        post(http, base, "/api/errands/" + id + "/grab", runner, "{}", UUID.randomUUID().toString());
        post(http, base, "/api/errands/" + id + "/confirm", runner, "{}", null);
        post(http, base, "/api/errands/" + id + "/pickup", runner, "{}", null);
        post(http, base, "/api/errands/" + id + "/deliver", runner, "{}", null);
        return id;
    }

    private static long publish(HttpClient http, String base, String publisher,
                                String runId, String label, int slots) throws Exception {
        String body = JSON.writeValueAsString(Map.of("campusId", 1, "type", "DELIVERY",
                "title", "jmeter_" + runId + "_" + label,
                "rewardCents", REWARD_CENTS, "slotTotal", slots));
        JsonNode data = post(http, base, "/api/errands", publisher, body, UUID.randomUUID().toString());
        long id = data.path("errandId").asLong(0);
        if (id <= 0) throw new IllegalStateException("Publish response lacked positive errandId");
        return id;
    }

    private static JsonNode post(HttpClient http, String base, String path, String token,
                                 String body, String requestId) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30));
        if (requestId != null) request.header("X-Request-Id", requestId);
        HttpResponse<String> response = http.send(request.POST(
                HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode root = JSON.readTree(response.body());
        if (response.statusCode() != 200 || !"OK".equals(root.path("code").asText())) {
            throw new IllegalStateException("S4/S1 fixture API failed at " + path + ": HTTP "
                    + response.statusCode() + " code=" + root.path("code").asText("missing"));
        }
        return root.path("data");
    }

    private static String requiredEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }

    private static int bounded(String text, int min, int max, String name) {
        int value;
        try { value = Integer.parseInt(text); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(name + " must be numeric", e); }
        if (value < min || value > max) throw new IllegalArgumentException(name + " must be " + min + ".." + max);
        return value;
    }

    private static String usage() {
        return "JMeterFixtureExporter s1 <localBaseUrl> <newOutputDir> <users> | "
                + "s1-distinct <localBaseUrl> <newOutputDir> <tasks> <threads> | "
                + "s3-read <localBaseUrl> <newOutputDir> <threads> <iterations> <uniform|hot90> | "
                + "s3-mixed <localBaseUrl> <newOutputDir> <threads> <iterations> | "
                + "s4 <localBaseUrl> <newOutputDir> <distinctTasks> <threads> <sameTaskAttempts> "
                + "[shared|distributed]";
    }
}
