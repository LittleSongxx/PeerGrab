package com.peergrab.bench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.shared.MessagePayloadCodec;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * S5 natural-expiry timeline. Seeds LOCKED tasks in a disposable stack and observes
 * the first durable state transition for each task. The MQ variant sends the same
 * delayed payload as the application; the fallback variant sends no MQ messages.
 *
 * Usage: S5TimelineProbe <mq|fallback> <count> <leadSeconds> <confirmSeconds>
 *        [timeoutAfterDueSeconds] [mqEndpoint]
 *
 * Run mq and fallback in separate fresh stacks: the persisted state alone cannot
 * reveal which worker path won a race. This tests Worker processing, not HTTP
 * grab/escrow or the application's local-message outbox.
 */
public final class S5TimelineProbe {
    private static final String TOPIC = "errand-confirm-timeout";
    private static final long ID_PREFIX = 900_100_000_000_000_000L;
    private static final long CANDIDATE_BASE = 50_000L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private S5TimelineProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) {
            throw new IllegalArgumentException("Usage: S5TimelineProbe <mq|fallback|mq-candidate|fallback-candidate> <count 1..10000> "
                    + "<leadSeconds >=15> <confirmSeconds >=1> [timeoutAfterDueSeconds >=10] [mqEndpoint]");
        }
        String mode = args[0];
        boolean candidateMode = mode.endsWith("-candidate");
        boolean mqMode = mode.equals("mq") || mode.equals("mq-candidate");
        if (!mqMode && !mode.equals("fallback") && !mode.equals("fallback-candidate")) {
            throw new IllegalArgumentException("Unsupported S5 mode");
        }
        int count = Integer.parseInt(args[1]);
        long leadSeconds = Long.parseLong(args[2]);
        long confirmSeconds = Long.parseLong(args[3]);
        long timeoutSeconds = args.length > 4 ? Long.parseLong(args[4]) : 300;
        String mqEndpoint = args.length > 5 ? args[5]
                : "127.0.0.1:" + System.getenv("PEERGRAB_TEST_MQ_PORT");
        if (count < 1 || count > 10_000 || leadSeconds < 15 || confirmSeconds < 1
                || timeoutSeconds < 10 || leadSeconds > 86_000) {
            throw new IllegalArgumentException("Invalid count, lead, confirmation timeout, or observation timeout");
        }
        if (candidateMode && confirmSeconds <= timeoutSeconds + 5) {
            throw new IllegalArgumentException("Candidate observation must end before the next confirmation deadline");
        }

        BenchSafety.requireDisposableStack();
        BenchSafety.requireMaintenanceWindow();
        BenchSafety.requireBenchmarkMqMode(mqMode);
        BenchSafety.requireTimeoutScanMode(!mqMode);
        BenchSafety.requireConfirmSeconds(confirmSeconds);
        if (mqMode) {
            BenchSafety.requireBenchmarkMqEndpoint(mqEndpoint);
        }
        String host = System.getenv("PEERGRAB_TEST_DB_HOST");
        String port = System.getenv("PEERGRAB_TEST_DB_PORT");
        String password = System.getenv().getOrDefault("PEERGRAB_TEST_DB_PASSWORD", "peergrab123456");
        String jdbcUrl = "jdbc:mysql://" + host + ":" + port
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";

        try (Connection db = DriverManager.getConnection(jdbcUrl, "root", password);
             BenchRunRecorder recorder = new BenchRunRecorder(jdbcUrl, "root", password)) {
            long dbNowMs = databaseNowMs(db);
            long skewMs = Math.abs(System.currentTimeMillis() - dbNowMs);
            if (skewMs > 1_000) {
                throw new IllegalStateException("Generator and MySQL clocks differ by " + skewMs
                        + " ms; synchronize clocks before an expiry measurement");
            }
            long dueMs = dbNowMs + leadSeconds * 1_000L;
            Metadata metadata = new Metadata(mode, leadSeconds, confirmSeconds, timeoutSeconds,
                    dueMs, workerScanInterval());
            long firstId = ID_PREFIX + (System.currentTimeMillis() % 100_000_000L) * 100_000L;
            String runId = recorder.startRun("BENCH", "S5-" + mode, count,
                    "natural expiry; worker mode=" + mode + "; source=DB status_log; no HTTP/outbox");
            try {
                seed(db, runId, firstId, count, dueMs, confirmSeconds);
                if (candidateMode) {
                    enqueueCandidates(db, firstId, count, dueMs);
                }
                if (mqMode) {
                    sendMessages(mqEndpoint, firstId, count, dueMs);
                }
                if (System.currentTimeMillis() >= dueMs - 5_000L) {
                    throw new IllegalStateException("Seeding/sending consumed the lead window; retry with larger leadSeconds");
                }
                System.out.printf("S5 run=%s mode=%s count=%d dueEpochMs=%d skewMs=%d%n",
                        runId, mode, count, dueMs, skewMs);
                long deadlineMs = dueMs + timeoutSeconds * 1_000L;
                int peakBusinessBacklog = 0;
                while (System.currentTimeMillis() < deadlineMs) {
                    long nowMs = databaseNowMs(db);
                    int pending = pendingCount(db, runId, candidateMode);
                    if (nowMs >= dueMs) {
                        peakBusinessBacklog = Math.max(peakBusinessBacklog, pending);
                        System.out.printf("S5 progress t=%+dms pending=%d completed=%d%n",
                                nowMs - dueMs, pending, count - pending);
                        if (pending == 0) break;
                    }
                    Thread.sleep(1_000);
                }
                Result result = collect(db, runId, count, dueMs, peakBusinessBacklog, candidateMode,
                        firstId);
                recorder.finishRun(runId, result.pass() ? "PASS" : "FAIL", result.json(metadata));
                System.out.println(result.json(metadata));
                System.out.printf("S5 business backlog peak=%d (not RocketMQ consumer lag)%n", peakBusinessBacklog);
                if (!result.pass()) System.exit(1);
            } catch (Exception e) {
                recorder.finishRun(runId, "FAIL", "{" + metadata.jsonFields()
                        + ",\"reason\":\"" + escapeJson(e.toString()) + "\"}");
                throw e;
            }
        }
    }

    private static long databaseNowMs(Connection db) throws SQLException {
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("SELECT ROUND(UNIX_TIMESTAMP(NOW(3))*1000)")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Read only a scan interval explicitly exposed by the live worker container. */
    private static ScanInterval workerScanInterval() throws IOException, InterruptedException {
        if ("container".equals(System.getenv("PEERGRAB_BENCH_RUNNER_CONTEXT"))) {
            String raw = System.getenv("PEERGRAB_BENCH_WORKER_SCAN_INTERVAL_MS");
            if (raw != null && raw.matches("[1-9][0-9]*")) {
                try {
                    return new ScanInterval(Long.parseLong(raw), "host-preflight-worker-env");
                } catch (NumberFormatException ignored) {
                    // An invalid value is recorded as unavailable, never invented.
                }
            }
            return new ScanInterval(null, "unavailable-in-container");
        }
        String project = System.getenv("PEERGRAB_BENCH_PROJECT");
        String ids = dockerOutput("ps", "-q", "--filter", "label=com.docker.compose.project=" + project,
                "--filter", "label=com.docker.compose.service=worker");
        String[] lines = ids.lines().filter(s -> !s.isBlank()).toArray(String[]::new);
        if (lines.length != 1) {
            return new ScanInterval(null, "unavailable");
        }
        String env = dockerOutput("inspect", "--format={{range .Config.Env}}{{println .}}{{end}}", lines[0]);
        for (String line : env.lines().toList()) {
            if (line.startsWith("PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS=")) {
                String raw = line.substring("PEERGRAB_TIMEOUT_SCAN_INTERVAL_MS=".length());
                try {
                    long value = Long.parseLong(raw);
                    return value > 0 ? new ScanInterval(value, "worker-env")
                            : new ScanInterval(null, "invalid-worker-env");
                } catch (NumberFormatException e) {
                    return new ScanInterval(null, "invalid-worker-env");
                }
            }
        }
        return new ScanInterval(null, "not-exposed-by-container-env");
    }

    private static String dockerOutput(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("docker");
        Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Docker inspect timed out while collecting S5 metadata");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("Docker inspect failed while collecting S5 metadata: " + output);
        }
        return output;
    }

    private static void seed(Connection db, String runId, long firstId, int count,
                             long dueMs, long confirmSeconds) throws SQLException {
        boolean oldAutoCommit = db.getAutoCommit();
        db.setAutoCommit(false);
        try (PreparedStatement errand = db.prepareStatement("""
                INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                                    reward_amount, slot_total, slot_taken, status, round, version,
                                    locked_at, confirm_deadline_at)
                VALUES (?, 1, 1001, ?, 'DELIVERY', ?, 100, 1, 1, 'LOCKED', 0, 2,
                        FROM_UNIXTIME(? / 1000.0), FROM_UNIXTIME(? / 1000.0))
                """);
             PreparedStatement item = db.prepareStatement("""
                INSERT INTO bench_run_item (run_id, entity_type, entity_id) VALUES (?, 'ERRAND', ?)
                """)) {
            long lockedAtMs = dueMs - confirmSeconds * 1_000L;
            for (int n = 0; n < count; n++) {
                long id = firstId + n;
                errand.setLong(1, id);
                errand.setLong(2, 2001 + n % 100);
                errand.setString(3, "bench_s5_natural_" + runId + "_" + n);
                errand.setLong(4, lockedAtMs);
                errand.setLong(5, dueMs);
                errand.addBatch();
                item.setString(1, runId);
                item.setLong(2, id);
                item.addBatch();
                if (n % 500 == 499) {
                    errand.executeBatch();
                    item.executeBatch();
                }
            }
            errand.executeBatch();
            item.executeBatch();
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(oldAutoCommit);
        }
    }

    private static void sendMessages(String endpoint, long firstId, int count, long dueMs) throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoint).setRequestTimeout(Duration.ofSeconds(10)).build();
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration).setTopics(TOPIC).build()) {
            for (int n = 0; n < count; n++) {
                if (System.currentTimeMillis() >= dueMs - 5_000L) {
                    throw new IllegalStateException("MQ sends reached the expiry window after " + n
                            + " messages; increase leadSeconds and use a fresh stack");
                }
                long id = firstId + n;
                String body = MessagePayloadCodec.timeout(id, 0, 2);
                Message message = provider.newMessageBuilder().setTopic(TOPIC)
                        .setKeys("timeout:" + id + ":0")
                        .setBody(body.getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTimestamp(dueMs).build();
                producer.send(message);
            }
        }
    }

    /** Enqueue through the public grab path so Redis scoring and eligibility match real traffic. */
    private static void enqueueCandidates(Connection db, long firstId, int count, long dueMs)
            throws Exception {
        String base = System.getenv("PEERGRAB_BENCH_BASE_URL");
        String secret = System.getenv("PEERGRAB_AUTH_JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("Candidate mode needs PEERGRAB_AUTH_JWT_SECRET");
        }
        BenchJwtTokens tokens = new BenchJwtTokens(secret, db);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1).build();
        for (int n = 0; n < count; n++) {
            if (System.currentTimeMillis() >= dueMs - 5_000L) {
                throw new IllegalStateException("Candidate setup reached expiry after " + n
                        + " tasks; increase leadSeconds and use a fresh stack");
            }
            long errandId = firstId + n;
            String token = tokens.issue(CANDIDATE_BASE + n);
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/errands/" + errandId + "/grab"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = JSON.readTree(response.body());
            if (response.statusCode() != 200 || !"SLOT_FULL".equals(root.path("code").asText())
                    || root.path("data").path("candidateRank").asLong(0) < 1) {
                throw new IllegalStateException("Candidate was not enqueued through grab API: index=" + n
                        + " HTTP=" + response.statusCode() + " code=" + root.path("code").asText());
            }
        }
        System.out.printf("S5 candidate fixtures enqueued=%d via public grab API%n", count);
    }

    private static int pendingCount(Connection db, String runId, boolean candidateMode) throws SQLException {
        String query = candidateMode ? """
                SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND'
                   AND e.status = 'LOCKED' AND e.round = 0
                """ : """
                SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND' AND e.status = 'LOCKED'
                """;
        try (PreparedStatement ps = db.prepareStatement(query)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static Result collect(Connection db, String runId, int expected, long dueMs,
                                  int peakBusinessBacklog, boolean candidateMode,
                                  long firstId) throws SQLException {
        List<Long> latencyMs = new ArrayList<>();
        int rows = 0, unprocessed = 0, duplicate = 0, premature = 0, wrongState = 0;
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT e.id, e.status, e.slot_taken, e.round, e.grabber_id,
                       COUNT(s.id) AS events,
                       ROUND(UNIX_TIMESTAMP(MIN(s.created_at))*1000) AS first_event_ms,
                       MIN(s.to_status) AS transition_to, MIN(s.operator_id) AS operator_id
                  FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                  LEFT JOIN errand_status_log s ON s.errand_id = e.id
                         AND s.from_status = 'LOCKED' AND s.round = 1
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND'
                 GROUP BY e.id, e.status, e.slot_taken, e.round, e.grabber_id
                """)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    int events = rs.getInt("events");
                    if (events > 1) duplicate++;
                    long eventMs = rs.getLong("first_event_ms");
                    boolean hasEvent = !rs.wasNull();
                    if (!hasEvent) {
                        unprocessed++;
                    } else {
                        long delta = eventMs - dueMs;
                        latencyMs.add(delta);
                        if (delta < 0) premature++;
                    }
                    long candidate = CANDIDATE_BASE + rs.getLong("id") - firstId;
                    boolean stateMatches = candidateMode
                            ? "LOCKED".equals(rs.getString("status"))
                                && rs.getInt("slot_taken") == 1 && rs.getInt("round") == 1
                                && rs.getLong("grabber_id") == candidate
                                && "LOCKED".equals(rs.getString("transition_to"))
                                && rs.getLong("operator_id") == candidate
                            : "PUBLISHED".equals(rs.getString("status"))
                                && rs.getInt("slot_taken") == 0 && rs.getInt("round") == 1
                                && "PUBLISHED".equals(rs.getString("transition_to"));
                    if (!stateMatches || events != 1) wrongState++;
                }
            }
        }
        Collections.sort(latencyMs);
        return new Result(expected, rows, latencyMs.size(), unprocessed, duplicate, premature,
                wrongState, peakBusinessBacklog,
                percentile(latencyMs, 50), percentile(latencyMs, 95),
                percentile(latencyMs, 99), latencyMs.isEmpty() ? -1 : latencyMs.get(latencyMs.size() - 1));
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return -1;
        int index = (int) Math.ceil(sorted.size() * (p / 100.0)) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    record ScanInterval(Long ms, String source) {}

    record Metadata(String mode, long leadSeconds, long confirmSeconds,
                    long timeoutAfterDueSeconds, long dueEpochMs, ScanInterval scanInterval) {
        String jsonFields() {
            String interval = scanInterval.ms() == null ? "null" : scanInterval.ms().toString();
            return String.format("\"mode\":\"%s\",\"leadSeconds\":%d,\"confirmSeconds\":%d,"
                            + "\"timeoutAfterDueSeconds\":%d,\"dueEpochMs\":%d,"
                            + "\"workerScanIntervalMs\":%s,\"workerScanIntervalSource\":\"%s\"",
                    mode, leadSeconds, confirmSeconds, timeoutAfterDueSeconds,
                    dueEpochMs, interval, scanInterval.source());
        }
    }

    record Result(int expected, int rows, int completed, int unprocessed, int duplicate,
                  int premature, int wrongState, int peakBusinessBacklog,
                  long p50Ms, long p95Ms, long p99Ms, long maxMs) {
        boolean pass() {
            return rows == expected && completed == expected && unprocessed == 0
                    && duplicate == 0 && premature == 0 && wrongState == 0;
        }

        String json(Metadata metadata) {
            return String.format("{%s,\"expected\":%d,\"rows\":%d,"
                            + "\"completed\":%d,\"unprocessed\":%d,\"duplicate\":%d,"
                            + "\"premature\":%d,\"wrongState\":%d,\"peakBusinessBacklog\":%d,"
                            + "\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d}",
                    metadata.jsonFields(), expected, rows, completed, unprocessed, duplicate, premature,
                    wrongState, peakBusinessBacklog, p50Ms, p95Ms, p99Ms, maxMs);
        }
    }
}
