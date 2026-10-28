package com.peergrab.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/** 保留任务的分批/异常隔离逻辑：不执行真实 SQL，只验证控制流与批次上限。 */
class DataRetentionJobTest {

    @Test
    @DisplayName("单表达到批大小继续删，直到不足一批；多表异常互不影响")
    void prunesInBatchesAndIsolatesTableFailures() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.responses.add(new int[]{500, 500, 12});  // local_message: 两满批 + 尾批
        jdbc.responses.add(new int[]{3});             // fund_event_outbox
        jdbc.responses.add(new int[]{0});             // publish_request
        jdbc.responses.add(new int[]{7});             // notification
        jdbc.responses.add(new int[]{1});             // sync_diff
        jdbc.responses.add(new int[]{0});             // grab_request

        DataRetentionJob job = new DataRetentionJob(jdbc, true, 500, 20,
                7, 30, 30, 180, 90, 30);
        job.run();

        assertEquals(6, jdbc.distinctTables(), "六张可清理表各清理一次（含分批重复调用）");
        assertEquals(3, jdbc.callsFor("local_message"), "满批后继续删，尾批后停止");
        assertEquals(1023, jdbc.totalRemoved);
    }

    @Test
    @DisplayName("某张表清理失败不阻断其它表")
    void tableFailureDoesNotAbortOthers() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.failOn("local_message");
        jdbc.responses.add(new int[]{1}); // fund_event_outbox
        jdbc.responses.add(new int[]{1}); // publish_request
        jdbc.responses.add(new int[]{1}); // notification
        jdbc.responses.add(new int[]{1}); // sync_diff

        new DataRetentionJob(jdbc, true, 500, 20, 7, 30, 30, 180, 90, 30).run();

        assertEquals(5, jdbc.successfulTables(), "local_message 失败后其余 5 张表仍继续");
    }

    @Test
    @DisplayName("关闭保留策略时不执行任何删除")
    void disabledSkipsEverything() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        new DataRetentionJob(jdbc, false, 500, 20, 7, 30, 30, 180, 90, 30).run();
        assertEquals(0, jdbc.totalCalls);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<int[]> responses = new ArrayList<>();
        private final List<String> tables = new ArrayList<>();
        private final List<String> failTables = new ArrayList<>();
        private int responseIndex;
        int totalRemoved;
        int totalCalls;

        private RecordingJdbcTemplate() {
            super(mock(DataSource.class));
        }

        @Override
        public int update(String sql, Object... args) {
            totalCalls++;
            String table = identify(sql);
            tables.add(table);
            if (failTables.contains(table)) {
                throw new IllegalStateException("simulated failure for " + table);
            }
            int[] planned = responseIndex < responses.size() ? responses.get(responseIndex) : new int[]{0};
            int perCallIndex = callsFor(table) - 1;
            int removed = perCallIndex < planned.length ? planned[perCallIndex] : 0;
            if (perCallIndex >= planned.length - 1) responseIndex++;
            totalRemoved += removed;
            return removed;
        }

        private void failOn(String table) {
            responseIndex++; // 为失败表占一个响应位
            failTables.add(table);
        }

        private int distinctTables() {
            return (int) tables.stream().distinct().count();
        }

        private int callsFor(String table) {
            return (int) tables.stream().filter(table::equals).count();
        }

        private int successfulTables() {
            return (int) tables.stream().distinct()
                    .filter(t -> !failTables.contains(t)).count();
        }

        private String identify(String sql) {
            for (String table : List.of("local_message", "fund_event_outbox", "publish_request",
                    "notification", "recon_diff", "sync_diff", "grab_request")) {
                if (sql.contains(" " + table) || sql.contains(table + "\n")) return table;
            }
            for (String table : List.of("local_message", "fund_event_outbox", "publish_request",
                    "notification", "recon_diff", "sync_diff", "grab_request")) {
                if (sql.contains(table)) return table;
            }
            throw new IllegalArgumentException(sql);
        }
    }
}
