package com.peergrab.it;

import com.peergrab.application.usecase.PublishErrandUseCase;
import com.peergrab.domain.errand.model.ErrandStatus;
import com.peergrab.domain.errand.model.ErrandType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v2 抢单的 HTTP 契约测试：覆盖 use case 单测覆盖不到的状态码、Header 与 202 查询。
 * 使用 test profile 的 X-User-Id 身份头，每个用例使用独立 runner id，避免共享限流桶。
 */
@SpringBootTest(properties = {"peergrab.mq.enabled=false"})
@AutoConfigureMockMvc
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class GrabV2HttpContractIT {

    @Autowired MockMvc mvc;
    @Autowired PublishErrandUseCase publishUseCase;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @BeforeAll
    static void requireMiddleware() {
        MiddlewareAvailable.require();
    }

    @BeforeEach
    void cleanLimiterKeys() {
        Set<String> keys = redis.keys("peergrab:rl:v1:{runner:25*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("缺少 X-Request-Id 返回 HTTP 400（不是业务异常包装的 200）")
    void missing_request_id_is_http_400() throws Exception {
        mvc.perform(post("/api/errands/1/grab-v2")
                        .header("X-User-Id", "25001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRound\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("非法 UUID 返回 HTTP 400")
    void invalid_uuid_is_http_400() throws Exception {
        mvc.perform(post("/api/errands/1/grab-v2")
                        .header("X-User-Id", "25001")
                        .header("X-Request-Id", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRound\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    @DisplayName("HTTP 首抢 200、重放 replayed=true、查询 SUCCEEDED")
    void grab_replay_and_query_over_http() throws Exception {
        long errandId = publish();
        String requestId = UUID.randomUUID().toString();

        mvc.perform(post("/api/errands/" + errandId + "/grab-v2")
                        .header("X-User-Id", "25002")
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRound\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.grabbed").value(true));

        mvc.perform(post("/api/errands/" + errandId + "/grab-v2")
                        .header("X-User-Id", "25002")
                        .header("X-Request-Id", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRound\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.replayed").value(true));

        mvc.perform(get("/api/errands/" + errandId + "/grab-requests/" + requestId)
                        .header("X-User-Id", "25002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("入口预算耗尽返回 429，且带 Retry-After（秒）")
    void rate_limited_has_retry_after_header() throws Exception {
        long errandId = publish();
        long runner = 25003L;
        String now = Long.toString(System.currentTimeMillis() + 60_000);
        redis.opsForHash().put("peergrab:rl:v1:{runner:" + runner + "}:all", "tokens", "0");
        redis.opsForHash().put("peergrab:rl:v1:{runner:" + runner + "}:all", "time", now);
        redis.opsForHash().put("peergrab:rl:v1:{runner:" + runner + "}:errand:" + errandId, "tokens", "0");
        redis.opsForHash().put("peergrab:rl:v1:{runner:" + runner + "}:errand:" + errandId, "time", now);

        mvc.perform(post("/api/errands/" + errandId + "/grab-v2")
                        .header("X-User-Id", String.valueOf(runner))
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRound\":0}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("GRAB_RATE_LIMITED"));
    }

    @Test
    @DisplayName("已登记的 PROCESSING 请求查询返回 202 + PROCESSING")
    void processing_query_returns_202() throws Exception {
        long errandId = publish();
        long runner = 25004L;
        String requestId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO grab_request (id, campus_id, errand_id, runner_id, operation, request_id,
                    payload_hash, request_round, base_errand_version, status, reservation_token, lease_until)
                VALUES (?, 1, ?, ?, 'GRAB', ?, ?, 0, 0, 'PROCESSING', ?, TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(3)))
                """,
                Math.abs(UUID.randomUUID().getMostSignificantBits()), errandId, runner, requestId,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", UUID.randomUUID().toString());

        mvc.perform(get("/api/errands/" + errandId + "/grab-requests/" + requestId)
                        .header("X-User-Id", String.valueOf(runner)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        jdbc.update("DELETE FROM grab_request WHERE errand_id = ? AND runner_id = ?", errandId, runner);
    }

    private long publish() {
        var result = publishUseCase.publish(new PublishErrandUseCase.Command(
                1L, 1001L, ErrandType.DELIVERY, "http-contract-" + UUID.randomUUID(), 800L, 1));
        assertEquals(ErrandStatus.PUBLISHED, result.status());
        return result.errandId();
    }
}
