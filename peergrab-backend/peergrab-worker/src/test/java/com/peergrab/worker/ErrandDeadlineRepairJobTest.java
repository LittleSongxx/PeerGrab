package com.peergrab.worker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class ErrandDeadlineRepairJobTest {

    @Test
    @DisplayName("按领域回退口径补回 NULL 截止时间，并统计仍无法修复的行数")
    void repairsMissingDeadlines() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.updates = List.of(3, 2);
        jdbc.unfixable = 1;

        new ErrandDeadlineRepairJob(jdbc, 300, 86400).repair();

        assertEquals(2, jdbc.calls.size());
        assertEquals("LOCKED", jdbc.calls.get(0));
        assertEquals("DELIVERED", jdbc.calls.get(1));
        assertEquals(300, jdbc.args.get(0)[0]);
        assertEquals(86400, jdbc.args.get(1)[0]);
    }

    @Test
    @DisplayName("单次 SQL 失败只告警，不向外抛异常")
    void sqlFailureIsIsolated() {
        RecordingJdbcTemplate jdbc = new RecordingJdbcTemplate();
        jdbc.fail = true;

        new ErrandDeadlineRepairJob(jdbc, 300, 86400).repair();

        assertEquals(0, jdbc.calls.size());
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        List<Integer> updates = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        int unfixable;
        boolean fail;
        private int index;

        private RecordingJdbcTemplate() {
            super(mock(DataSource.class));
        }

        @Override
        public int update(String sql, Object... updateArgs) {
            if (fail) throw new IllegalStateException("simulated db failure");
            calls.add(sql.contains("LOCKED") ? "LOCKED" : "DELIVERED");
            args.add(updateArgs);
            int result = index < updates.size() ? updates.get(index) : 0;
            index++;
            return result;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            @SuppressWarnings("unchecked")
            T value = (T) Integer.valueOf(unfixable);
            return value;
        }
    }
}
