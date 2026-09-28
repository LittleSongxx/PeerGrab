package com.peergrab.it;

import com.peergrab.application.usecase.CreditCalibrationUseCase;
import com.peergrab.domain.credit.model.CreditEvent;
import com.peergrab.domain.credit.model.CreditEventType;
import com.peergrab.domain.credit.ports.CreditRepository;
import com.peergrab.infrastructure.persistence.JdbcCreditRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P6：信用分每日校准。
 *
 * P5 的业务事务会增量更新 credit_score；P6 的每日 job 用 credit_event 的 30 天窗口重算快照，
 * 把过期事件的影响移除，避免分数永久带着窗口外历史。
 */
@SpringBootTest(properties = {"peergrab.mq.enabled=false"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class CreditCalibrationIT {

    static final long USER = 7201L;

    @Autowired JdbcTemplate jdbc;
    @Autowired CreditRepository creditRepository;
    @Autowired CreditCalibrationUseCase calibrationUseCase;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM credit_event WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM credit_score WHERE user_id = ?", USER);
    }

    @Test
    @DisplayName("每日校准按 30 天窗口重算，移除过期事件贡献")
    void calibration_rebuilds_score_from_recent_window() {
        jdbc.update("""
                INSERT INTO credit_event (id, biz_no, user_id, type, delta, ref_type, ref_id, created_at)
                VALUES
                  (7201001, 'old_timeout:7201', ?, 'GRAB_TIMEOUT_REVERT', -5, 'ERRAND', 1, DATE_SUB(NOW(3), INTERVAL 31 DAY)),
                  (7201002, 'recent_settle:7201', ?, 'SETTLE', 2, 'ERRAND', 2, DATE_SUB(NOW(3), INTERVAL 1 DAY))
                """, USER, USER);
        jdbc.update("INSERT INTO credit_score (user_id, score, version) VALUES (?, 57, 1)", USER);

        int changed = calibrationUseCase.calibrate(CreditEventType.WINDOW_DAYS, 100);

        assertEquals(1, changed, "应修正一条快照");
        assertEquals(62, creditRepository.scoreOf(USER), "校准后只保留 30 天窗口内的 +2");
    }

    @Test
    @DisplayName("校准读完事件后出现新事件时，版本 CAS 不覆盖新分数")
    void calibration_does_not_overwrite_concurrent_credit_event() {
        jdbc.update("""
                INSERT INTO credit_event (id, biz_no, user_id, type, delta, ref_type, ref_id, created_at)
                VALUES (7201010, 'old_timeout:7201', ?, 'GRAB_TIMEOUT_REVERT', -5, 'ERRAND', 1,
                        DATE_SUB(NOW(3), INTERVAL 31 DAY))
                """, USER);
        jdbc.update("INSERT INTO credit_score (user_id, score, version) VALUES (?, 55, 1)", USER);

        AtomicBoolean injected = new AtomicBoolean();
        JdbcTemplate interleavingJdbc = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) {
                List<T> result = super.queryForList(sql, elementType, args);
                if (sql.contains("SELECT delta FROM credit_event") && injected.compareAndSet(false, true)) {
                    creditRepository.applyEvent(new CreditEvent(
                            7201011L, "late_settle:7201", USER, CreditEventType.SETTLE,
                            CreditEventType.SETTLE.delta(), "ERRAND", 2L, Instant.now()));
                }
                return result;
            }
        };

        int changed = new JdbcCreditRepository(interleavingJdbc, new SimpleMeterRegistry())
                .calibrateScores(CreditEventType.WINDOW_DAYS, 100);

        assertEquals(0, changed, "并发业务事件已修正快照，校准不能再覆盖它");
        assertEquals(62, creditRepository.scoreOf(USER), "重算必须包含新事件，不能覆盖其贡献");
    }

    @Test
    @DisplayName("达到上限后再扣分，实时快照与校准保持一致")
    void cap_then_penalty_keeps_same_score_after_calibration() {
        for (int i = 0; i < 30; i++) {
            creditRepository.applyEvent(new CreditEvent(7202000L + i,
                    "cap:" + i + ":7201", USER, CreditEventType.SETTLE, 2,
                    "ERRAND", 100L + i, Instant.now()));
        }
        creditRepository.applyEvent(new CreditEvent(7202030L, "penalty:7201", USER,
                CreditEventType.GRAB_TIMEOUT_REVERT, -5, "ERRAND", 200L, Instant.now()));

        assertEquals(95, creditRepository.scoreOf(USER));
        assertEquals(0, calibrationUseCase.calibrate(CreditEventType.WINDOW_DAYS, 100));
        assertEquals(95, creditRepository.scoreOf(USER));
    }
}
