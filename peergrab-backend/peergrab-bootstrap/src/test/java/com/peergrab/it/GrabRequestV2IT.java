package com.peergrab.it;

import com.peergrab.application.usecase.GrabErrandUseCase;
import com.peergrab.application.usecase.GrabRequestRecoveryUseCase;
import com.peergrab.application.usecase.GrabRequestUseCase;
import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import com.peergrab.shared.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 持久幂等抢单请求（v2）集成测试。
 *
 * <p>验证：
 * <ul>
 *   <li>同一 requestId 重放只产生一条业务事实；</li>
 *   <li>同 requestId 换请求内容返回 REQUEST_CONFLICT；</li>
 *   <li>确定失败也会被持久化并可重放；</li>
 *   <li>恢复任务能把“业务已提交但请求行未终结”收敛为 SUCCEEDED，
 *       把“未提交且租约过期”收敛为 EXPIRED。</li>
 * </ul>
 * 需要可丢弃的 MySQL/Redis；仅在 -Dpeergrab.it=true 时执行。</p>
 */
@SpringBootTest(properties = {"peergrab.credit.max-ongoing=10000"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class GrabRequestV2IT {

    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired GrabRequestUseCase grabRequestUseCase;
    @Autowired GrabRequestRecoveryUseCase recoveryUseCase;
    @Autowired GrabErrandUseCase legacyGrabUseCase;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @Test
    @DisplayName("同一 requestId 重放只产生一条 grab_record，第二次标记 replayed")
    void same_request_id_creates_single_business_fact() {
        long errandId = publish();
        String requestId = UUID.randomUUID().toString();

        var first = grabRequestUseCase.grab(errandId, 2001L, requestId, 0);
        assertTrue(first.grabbed(), "首次请求应抢中: " + first);
        assertEquals(ErrorCode.OK, first.code());
        assertEquals(200, first.httpStatus());

        var replay = grabRequestUseCase.grab(errandId, 2001L, requestId, 0);
        assertTrue(replay.grabbed(), "重放应返回同一成功结果");
        assertTrue(replay.replayed(), "重放必须标记 replayed=true");

        Integer records = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_record WHERE errand_id = ? AND result = 'GRABBED'",
                Integer.class, errandId);
        assertEquals(1, records);
        Integer requests = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_request WHERE errand_id = ? AND status = 'SUCCEEDED'",
                Integer.class, errandId);
        assertEquals(1, requests);
    }

    @Test
    @DisplayName("同 requestId 更换轮次返回 REQUEST_CONFLICT，不执行第二次抢单")
    void same_request_id_with_different_payload_conflicts() {
        long errandId = publish();
        String requestId = UUID.randomUUID().toString();
        var first = grabRequestUseCase.grab(errandId, 2001L, requestId, 0);
        assertTrue(first.grabbed());

        var conflict = grabRequestUseCase.grab(errandId, 2001L, requestId, 1);
        assertEquals(ErrorCode.REQUEST_CONFLICT, conflict.code());
        assertEquals(409, conflict.httpStatus());
    }

    @Test
    @DisplayName("确定失败结果也被持久化，重放返回同一失败码")
    void terminal_failure_is_persisted_and_replayed() {
        long errandId = publish();
        String winnerRequest = UUID.randomUUID().toString();
        assertTrue(grabRequestUseCase.grab(errandId, 2001L, winnerRequest, 0).grabbed());

        String loserRequest = UUID.randomUUID().toString();
        var lost = grabRequestUseCase.grab(errandId, 2002L, loserRequest, 0);
        assertFalse(lost.grabbed());
        assertEquals(ErrorCode.SLOT_FULL, lost.code());

        var replay = grabRequestUseCase.grab(errandId, 2002L, loserRequest, 0);
        assertFalse(replay.grabbed());
        assertTrue(replay.replayed());
        assertEquals(ErrorCode.SLOT_FULL, replay.code());
        Integer requests = jdbc.queryForObject(
                "SELECT COUNT(*) FROM grab_request WHERE errand_id = ? AND status = 'REJECTED'",
                Integer.class, errandId);
        assertEquals(1, requests);
    }

    @Test
    @DisplayName("恢复任务把未提交且租约过期的请求收敛为 EXPIRED")
    void recovery_expires_stale_request() {
        long errandId = publish();
        String requestId = UUID.randomUUID().toString();
        insertStaleRequest(errandId, 2001L, requestId, "PROCESSING");

        recoveryUseCase.expireBatch(200);

        String status = jdbc.queryForObject(
                "SELECT status FROM grab_request WHERE errand_id = ? AND request_id = ?",
                String.class, errandId, requestId);
        assertEquals("EXPIRED", status);
    }

    @Test
    @DisplayName("恢复任务把已提交但请求行未终结的请求收敛为 SUCCEEDED")
    void recovery_recovers_committed_request() {
        long errandId = publish();
        String requestId = UUID.randomUUID().toString();
        // 先用 v1 用例完成抢单事实（grab_record 已落库），再伪造一条未终结的请求行。
        var grabbed = legacyGrabUseCase.grab(new GrabErrandUseCase.Command(errandId, 2001L, requestId));
        assertTrue(grabbed.grabbed(), "前置抢单应成功: " + grabbed);
        insertStaleRequest(errandId, 2001L, requestId, "PROCESSING");

        recoveryUseCase.expireBatch(200);

        String status = jdbc.queryForObject(
                "SELECT status FROM grab_request WHERE errand_id = ? AND request_id = ?",
                String.class, errandId, requestId);
        assertEquals("SUCCEEDED", status);
    }

    private long publish() {
        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "v2-幂等测试-" + UUID.randomUUID(), 800L, 1));
        assertEquals(ErrandStatus.PUBLISHED, result.status());
        return result.errandId();
    }

    private void insertStaleRequest(long errandId, long runnerId, String requestId, String status) {
        jdbc.update("""
                INSERT INTO grab_request (id, campus_id, errand_id, runner_id, operation, request_id,
                    payload_hash, request_round, base_errand_version, status, reservation_token, lease_until)
                VALUES (?, 1, ?, ?, 'GRAB', ?, ?, 0, 0, ?, ?, TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3)))
                """,
                Math.abs(UUID.randomUUID().getMostSignificantBits()),
                errandId, runnerId, requestId,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                status, UUID.randomUUID().toString());
    }
}
