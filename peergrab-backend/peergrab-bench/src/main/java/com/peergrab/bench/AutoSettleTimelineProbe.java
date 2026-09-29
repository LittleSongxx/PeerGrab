package com.peergrab.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peergrab.shared.MessagePayloadCodec;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent DELIVERED -> SETTLED natural-expiry probe. It does not measure S5
 * confirmation timeout, HTTP delivery, or the application's local-message sender.
 * The combined mode deliberately reports MQ plus the always-on auto-settle scan.
 */
public final class AutoSettleTimelineProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOPIC = "errand-auto-settle";
    private static final long ID_PREFIX = 900_200_000_000_000_000L;
    private static final int REWARD_CENTS = 1_000;
    private static final int RUNNER_CENTS = 950;
    private static final int COMMISSION_CENTS = 50;
    private static final int MAX_COUNT = 2_000;

    private AutoSettleTimelineProbe() {}

    record Config(String mode, int count, long leadSeconds, long timeoutSeconds, String mqEndpoint) {
        boolean combined() { return "combined".equals(mode); }
    }

    static Config parse(String[] args) {
        if (args.length < 4 || args.length > 5) {
            throw new IllegalArgumentException("Usage: AutoSettleTimelineProbe "
                    + "<scan|combined> <count 1..2000> <leadSeconds >=15> "
                    + "<timeoutAfterDueSeconds >=10> [mqEndpoint]");
        }
        String mode = args[0];
        if (!"scan".equals(mode) && !"combined".equals(mode)) {
            throw new IllegalArgumentException("Mode must be scan or combined");
        }
        int count = Integer.parseInt(args[1]);
        long lead = Long.parseLong(args[2]);
        long timeout = Long.parseLong(args[3]);
        if (count < 1 || count > MAX_COUNT || lead < 15 || lead > 86_000 || timeout < 10) {
            throw new IllegalArgumentException("Count, lead, or observation timeout outside permitted bounds");
        }
        String endpoint = args.length == 5 ? args[4] : null;
        if ("combined".equals(mode) && (endpoint == null || endpoint.isBlank())) {
            throw new IllegalArgumentException("Combined mode needs the verified benchmark MQ endpoint");
        }
        if ("scan".equals(mode) && endpoint != null) {
            throw new IllegalArgumentException("Scan-only mode does not accept an MQ endpoint");
        }
        return new Config(mode, count, lead, timeout, endpoint);
    }

    public static void main(String[] args) throws Exception {
        Config cfg = parse(args);
        BenchSafety.requireDisposableStack();
        BenchSafety.requireMaintenanceWindow();
        BenchSafety.requireBenchmarkMqMode(cfg.combined());
        if (cfg.combined()) BenchSafety.requireBenchmarkMqEndpoint(cfg.mqEndpoint());
        long scanIntervalMs = verifiedScanInterval();

        String host = System.getenv("PEERGRAB_TEST_DB_HOST");
        String port = System.getenv("PEERGRAB_TEST_DB_PORT");
        String password = System.getenv("PEERGRAB_TEST_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("Missing disposable benchmark database credential");
        }
        String url = "jdbc:mysql://" + host + ":" + port
                + "/peer_grab?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
        try (Connection db = DriverManager.getConnection(url, "root", password);
             BenchRunRecorder recorder = new BenchRunRecorder(url, "root", password)) {
            requireFresh(db);
            long skewMs = Math.abs(System.currentTimeMillis() - databaseNowMs(db));
            if (skewMs > 1_000) {
                throw new IllegalStateException("Generator and MySQL clocks differ by " + skewMs + " ms");
            }
            long base = ID_PREFIX + (System.currentTimeMillis() % 100_000_000L) * 100_000L;
            String runId = recorder.startRun("BENCH", "AUTO-SETTLE-" + cfg.mode(), cfg.count(),
                    "direct DELIVERED/HELD fixture with balanced escrow ledger; "
                            + "source=DB status_log; no HTTP delivery/local-message sender");
            Map<String, Object> summary = new LinkedHashMap<>();
            boolean pass = false;
            try {
                seed(db, runId, base, cfg.count());
                long totalBefore = scalar(db,
                        "SELECT COALESCE(SUM(available + frozen),0) FROM wallet_account");
                long dueMs = databaseNowMs(db) + cfg.leadSeconds() * 1_000L;
                schedule(db, runId, dueMs, cfg.count());
                summary.put("mode", cfg.mode());
                summary.put("count", cfg.count());
                summary.put("leadSeconds", cfg.leadSeconds());
                summary.put("timeoutAfterDueSeconds", cfg.timeoutSeconds());
                summary.put("dueEpochMs", dueMs);
                summary.put("clockSkewMs", skewMs);
                summary.put("workerScanIntervalMs", scanIntervalMs);
                summary.put("attribution", cfg.combined() ? "MQ+always-on-auto-settle-scan" : "scan-only");
                if (cfg.combined()) sendMessages(cfg.mqEndpoint(), base, cfg.count(), dueMs);
                if (System.currentTimeMillis() >= dueMs - 5_000L) {
                    throw new IllegalStateException("Fixture/send consumed the lead window; use a fresh stack and longer lead");
                }
                System.out.printf("Auto-settle run=%s mode=%s count=%d dueEpochMs=%d%n",
                        runId, cfg.mode(), cfg.count(), dueMs);
                int peakBacklog = observe(db, runId, dueMs, cfg.timeoutSeconds(), cfg.count());
                summary.putAll(collect(db, runId, cfg.count(), dueMs, totalBefore, peakBacklog));
                pass = Boolean.TRUE.equals(summary.get("correct"));
            } catch (Exception e) {
                summary.put("reason", e.toString());
                throw e;
            } finally {
                recorder.finishRun(runId, pass ? "PASS" : "FAIL", JSON.writeValueAsString(summary));
                System.out.println(JSON.writeValueAsString(summary));
                System.out.printf("Auto-settle runId=%s status=%s%n", runId, pass ? "PASS" : "FAIL");
            }
            if (!pass) throw new IllegalStateException("Automatic settlement correctness gate failed");
        }
    }

    private static void requireFresh(Connection db) throws SQLException {
        for (String table : List.of("errand", "escrow_order", "wallet_ledger", "bench_run",
                "credit_event", "fund_event_outbox")) {
            if (scalar(db, "SELECT COUNT(*) FROM " + table) != 0) {
                throw new IllegalStateException("Auto-settle probe needs a fresh disposable stack: " + table);
            }
        }
        if (scalar(db, "SELECT COUNT(*) FROM wallet_account") != 5
                || scalar(db, "SELECT COALESCE(SUM(available + frozen + version),0) "
                    + "FROM wallet_account WHERE owner_type IN ('ESCROW','COMMISSION')") != 0) {
            throw new IllegalStateException("Expected untouched benchmark seed wallets");
        }
    }

    /** Seed each HELD escrow with balanced publication ledger and enough escrow funds. */
    private static void seed(Connection db, String runId, long base, int count) throws SQLException {
        boolean oldAutoCommit = db.getAutoCommit();
        db.setAutoCommit(false);
        try (PreparedStatement account = db.prepareStatement("""
                INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version)
                VALUES (?, ?, 'USER', ?, 0, ?)
                """);
             PreparedStatement errand = db.prepareStatement("""
                INSERT INTO errand (id, campus_id, publisher_id, grabber_id, type, title,
                                    reward_amount, slot_total, slot_taken, status, round, version,
                                    delivered_at, auto_settle_deadline_at)
                VALUES (?, 1, ?, ?, 'DELIVERY', ?, ?, 1, 1, 'DELIVERED', 0, 5,
                        DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 1 DAY))
                """);
             PreparedStatement escrow = db.prepareStatement("""
                INSERT INTO escrow_order (id, campus_id, errand_id, publisher_id, amount, status)
                VALUES (?, 1, ?, ?, ?, 'HELD')
                """);
             PreparedStatement ledger = db.prepareStatement("""
                INSERT INTO wallet_ledger (id, biz_no, account_id, user_id, direction, amount,
                                           balance_after, account_version, ref_type, ref_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ESCROW', ?)
                """);
             PreparedStatement item = db.prepareStatement("""
                INSERT INTO bench_run_item (run_id, entity_type, entity_id) VALUES (?, 'ERRAND', ?)
                """)) {
            long publisher = base + 5_000;
            account.setLong(1, publisher);
            account.setLong(2, publisher);
            account.setLong(3, (long) count * REWARD_CENTS);
            account.setLong(4, 0);
            account.executeUpdate();
            for (int n = 0; n < count; n++) {
                long runner = base + 5_001 + n;
                account.setLong(1, runner);
                account.setLong(2, runner);
                account.setLong(3, 0);
                account.setLong(4, 0);
                account.addBatch();
            }
            account.executeBatch();
            for (int n = 0; n < count; n++) {
                long id = base + n;
                long runner = base + 5_001 + n;
                errand.setLong(1, id);
                errand.setLong(2, publisher);
                errand.setLong(3, runner);
                errand.setString(4, "bench_auto_settle_" + n);
                errand.setInt(5, REWARD_CENTS);
                errand.addBatch();
                escrow.setLong(1, base + 10_000 + n);
                escrow.setLong(2, id);
                escrow.setLong(3, publisher);
                escrow.setInt(4, REWARD_CENTS);
                escrow.addBatch();
                String bizNo = "escrow:" + id;
                ledger.setLong(1, base + 20_000 + 2L * n);
                ledger.setString(2, bizNo);
                ledger.setLong(3, publisher);
                ledger.setLong(4, publisher);
                ledger.setString(5, "DEBIT");
                ledger.setInt(6, REWARD_CENTS);
                ledger.setLong(7, (long) (count - n - 1) * REWARD_CENTS);
                ledger.setInt(8, n + 1);
                ledger.setLong(9, id);
                ledger.addBatch();
                ledger.setLong(1, base + 20_001 + 2L * n);
                ledger.setLong(3, 1);
                ledger.setLong(4, publisher);
                ledger.setString(5, "CREDIT");
                ledger.setLong(7, (long) (n + 1) * REWARD_CENTS);
                ledger.addBatch();
                item.setString(1, runId);
                item.setLong(2, id);
                item.addBatch();
                if (n % 200 == 199) {
                    errand.executeBatch();
                    escrow.executeBatch();
                    ledger.executeBatch();
                    item.executeBatch();
                }
            }
            errand.executeBatch();
            escrow.executeBatch();
            ledger.executeBatch();
            item.executeBatch();
            try (PreparedStatement update = db.prepareStatement(
                    "UPDATE wallet_account SET available = ?, version = ? WHERE id = ?")) {
                update.setLong(1, 0);
                update.setInt(2, count);
                update.setLong(3, publisher);
                if (update.executeUpdate() != 1) throw new SQLException("Publisher wallet update failed");
                update.setLong(1, (long) count * REWARD_CENTS);
                update.setLong(3, 1);
                if (update.executeUpdate() != 1) throw new SQLException("Escrow wallet update failed");
            }
            db.commit();
        } catch (SQLException e) {
            db.rollback();
            throw e;
        } finally {
            db.setAutoCommit(oldAutoCommit);
        }
    }

    private static void schedule(Connection db, String runId, long dueMs, int count) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                UPDATE errand e JOIN bench_run_item i ON i.entity_id = e.id
                   SET e.auto_settle_deadline_at = FROM_UNIXTIME(? / 1000.0),
                       e.delivered_at = DATE_SUB(FROM_UNIXTIME(? / 1000.0), INTERVAL 1 DAY)
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND' AND e.status = 'DELIVERED'
                """)) {
            ps.setLong(1, dueMs);
            ps.setLong(2, dueMs);
            ps.setString(3, runId);
            if (ps.executeUpdate() != count) throw new SQLException("Could not schedule every fixture");
        }
    }

    private static void sendMessages(String endpoint, long base, int count, long dueMs) throws Exception {
        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoint).setRequestTimeout(Duration.ofSeconds(10)).build();
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration).setTopics(TOPIC).build()) {
            for (int n = 0; n < count; n++) {
                if (System.currentTimeMillis() >= dueMs - 5_000L) {
                    throw new IllegalStateException("MQ send reached expiry after " + n
                            + " tasks; use a fresh stack and longer lead");
                }
                long id = base + n;
                Message message = provider.newMessageBuilder().setTopic(TOPIC)
                        .setKeys("autosettle:" + id)
                        .setBody(MessagePayloadCodec.autoSettle(id).getBytes(StandardCharsets.UTF_8))
                        .setDeliveryTimestamp(dueMs).build();
                producer.send(message);
            }
        }
    }

    private static int observe(Connection db, String runId, long dueMs, long timeoutSeconds,
                               int count) throws Exception {
        int peak = 0;
        while (System.currentTimeMillis() < dueMs + timeoutSeconds * 1_000L) {
            long nowMs = databaseNowMs(db);
            int pending;
            try (PreparedStatement ps = db.prepareStatement("""
                    SELECT COUNT(*) FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                     WHERE i.run_id = ? AND i.entity_type = 'ERRAND' AND e.status = 'DELIVERED'
                    """)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    pending = rs.getInt(1);
                }
            }
            if (nowMs >= dueMs) {
                peak = Math.max(peak, pending);
                System.out.printf("Auto-settle progress t=%+dms pending=%d completed=%d%n",
                        nowMs - dueMs, pending, count - pending);
                if (pending == 0) break;
            }
            Thread.sleep(1_000);
        }
        return peak;
    }

    private static Map<String, Object> collect(Connection db, String runId, int expected,
                                               long dueMs, long totalBefore, int peak) throws SQLException {
        List<Long> latencies = new ArrayList<>();
        int rows = 0, unprocessed = 0, duplicate = 0, premature = 0, wrongState = 0;
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT e.status, e.slot_taken, e.round, e.version, o.status AS escrow_status,
                       COUNT(s.id) AS events,
                       ROUND(UNIX_TIMESTAMP(MIN(s.created_at))*1000) AS event_ms,
                       SUM(CASE WHEN s.id IS NOT NULL AND s.to_status = 'SETTLED'
                                 AND s.operator_id = -1 AND s.round = 0 THEN 1 ELSE 0 END) AS valid_events
                  FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                  JOIN escrow_order o ON o.errand_id = e.id
                  LEFT JOIN errand_status_log s ON s.errand_id = e.id
                       AND s.from_status = 'DELIVERED'
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND'
                 GROUP BY e.id, e.status, e.slot_taken, e.round, e.version, o.status
                """)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows++;
                    int events = rs.getInt("events");
                    if (events > 1) duplicate++;
                    long eventMs = rs.getLong("event_ms");
                    if (rs.wasNull()) {
                        unprocessed++;
                    } else {
                        long latency = eventMs - dueMs;
                        latencies.add(latency);
                        if (latency < 0) premature++;
                    }
                    if (!"SETTLED".equals(rs.getString("status"))
                            || !"RELEASED".equals(rs.getString("escrow_status"))
                            || rs.getInt("slot_taken") != 1 || rs.getInt("round") != 0
                            || rs.getLong("version") != 6 || rs.getInt("valid_events") != 1
                            || events != 1) wrongState++;
                }
            }
        }
        int badLedger = 0, badCredit = 0, badOutbox = 0;
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT e.id,
                       COUNT(DISTINCT l.id) AS legs,
                       COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END),0) AS net,
                       SUM(CASE WHEN l.account_id = 1 AND l.direction = 'DEBIT'
                                 AND l.amount = ? THEN 1 ELSE 0 END) AS escrow_debits,
                       SUM(CASE WHEN l.account_id = e.grabber_id AND l.direction = 'CREDIT'
                                 AND l.amount = ? THEN 1 ELSE 0 END) AS runner_credits,
                       SUM(CASE WHEN l.account_id = 2 AND l.direction = 'CREDIT'
                                 AND l.amount = ? THEN 1 ELSE 0 END) AS commission_credits,
                       COUNT(DISTINCT c.id) AS credit_events, COUNT(DISTINCT f.biz_no) AS outbox_events
                  FROM bench_run_item i JOIN errand e ON e.id = i.entity_id
                  LEFT JOIN wallet_ledger l ON l.biz_no = CONCAT('settle:', e.id)
                  LEFT JOIN credit_event c ON c.biz_no = CONCAT('settle:', e.id)
                       AND c.user_id = e.grabber_id AND c.type = 'SETTLE' AND c.delta = 2
                  LEFT JOIN fund_event_outbox f ON f.biz_no = CONCAT('settle:', e.id)
                       AND f.event_type = 'SETTLED'
                 WHERE i.run_id = ? AND i.entity_type = 'ERRAND'
                 GROUP BY e.id
                """)) {
            ps.setInt(1, REWARD_CENTS);
            ps.setInt(2, RUNNER_CENTS);
            ps.setInt(3, COMMISSION_CENTS);
            ps.setString(4, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("legs") != 3 || rs.getLong("net") != 0
                            || rs.getInt("escrow_debits") != 1 || rs.getInt("runner_credits") != 1
                            || rs.getInt("commission_credits") != 1) badLedger++;
                    if (rs.getInt("credit_events") != 1) badCredit++;
                    if (rs.getInt("outbox_events") != 1) badOutbox++;
                }
            }
        }
        Collections.sort(latencies);
        long totalAfter = scalar(db, "SELECT COALESCE(SUM(available + frozen),0) FROM wallet_account");
        long debitCredit = scalar(db, """
                SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END),0)
                  FROM wallet_ledger
                """);
        long snapshotDiffs = scalar(db, """
                SELECT COUNT(*) FROM wallet_account a WHERE a.owner_type IN ('ESCROW','COMMISSION')
                   AND a.available + a.frozen <> COALESCE((SELECT SUM(CASE WHEN l.direction = 'CREDIT'
                       THEN l.amount ELSE -l.amount END) FROM wallet_ledger l WHERE l.account_id=a.id),0)
                """);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("expected", expected);
        result.put("rows", rows);
        result.put("completed", latencies.size());
        result.put("unprocessed", unprocessed);
        result.put("duplicateStatusLogs", duplicate);
        result.put("premature", premature);
        result.put("wrongStateOrEscrow", wrongState);
        result.put("badSettlementLedger", badLedger);
        result.put("badCreditEvents", badCredit);
        result.put("badFundOutboxEvents", badOutbox);
        result.put("totalBalanceDeltaCents", totalAfter - totalBefore);
        result.put("globalDebitCreditDiffCents", debitCredit);
        result.put("systemSnapshotDiffs", snapshotDiffs);
        result.put("peakBusinessBacklog", peak);
        result.put("p50Ms", percentile(latencies, 50));
        result.put("p95Ms", percentile(latencies, 95));
        result.put("p99Ms", percentile(latencies, 99));
        result.put("maxMs", latencies.isEmpty() ? -1 : latencies.get(latencies.size() - 1));
        result.put("correct", rows == expected && latencies.size() == expected && unprocessed == 0
                && duplicate == 0 && premature == 0 && wrongState == 0 && badLedger == 0
                && badCredit == 0 && badOutbox == 0 && totalAfter == totalBefore
                && debitCredit == 0 && snapshotDiffs == 0);
        return result;
    }

    static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return -1;
        int index = (int) Math.ceil(sorted.size() * p / 100.0) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long databaseNowMs(Connection db) throws SQLException {
        return scalar(db, "SELECT ROUND(UNIX_TIMESTAMP(NOW(3))*1000)");
    }

    private static long verifiedScanInterval() {
        String raw = System.getenv("PEERGRAB_BENCH_VERIFIED_AUTO_SETTLE_SCAN_INTERVAL_MS");
        if (raw == null || !raw.matches("[1-9][0-9]*")) {
            throw new IllegalStateException("Missing host-inspected worker auto-settle scan interval");
        }
        return Long.parseLong(raw);
    }

    private static long scalar(Connection db, String sql) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
