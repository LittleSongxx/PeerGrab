package com.peergrab.it;

import com.peergrab.domain.auth.ports.AuthPort;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Session vs JWT 对照实验：同一个 AuthPort 接口，两种实现的行为差异。
 *
 * 两个 @Nested 类各自带不同的 peergrab.auth.mode，Spring 会建两个独立上下文。
 *
 * ── 对照的核心问题 ──
 *   1. Redis 状态丢失后 token 是否还有效？
 *      Session：失效（映射没了）；JWT：仍有效（MySQL 保存会话真值）
 *   2. 登出后 token 是否立即失效？
 *      Session：删 Redis 映射；JWT：撤销 MySQL 会话。
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class AuthModeIT {

    @Nested
    @SpringBootTest(properties = {"peergrab.auth.mode=session", "peergrab.mq.enabled=false"})
    class SessionMode {

        @Autowired AuthPort authPort;
        @Autowired StringRedisTemplate redis;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        }

        @Test
        @DisplayName("Session：token 依赖服务端映射，映射丢失即失效")
        void session_depends_on_server_state() {
            String token = authPort.login(7001);
            assertEquals(7001, authPort.resolve(token).orElseThrow());

            // 模拟服务端状态丢失：删掉会话映射（等价于 Redis 清空/换实例）
            redis.delete("auth:token:" + token);
            assertTrue(authPort.resolve(token).isEmpty(),
                    "Session 模式下映射丢失后 token 必须失效");
        }

        @Test
        @DisplayName("Session：登出立即失效")
        void session_logout_invalidates_immediately() {
            String token = authPort.login(7002);
            authPort.logout(token);
            assertTrue(authPort.resolve(token).isEmpty());
        }
    }

    @Nested
    @SpringBootTest(properties = {"peergrab.auth.mode=jwt", "peergrab.mq.enabled=false"})
    class JwtMode {

        @Autowired AuthPort authPort;
        @Autowired StringRedisTemplate redis;

        @BeforeAll
        static void requireMiddleware() {
            Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
        }

        @Test
        @DisplayName("JWT：不依赖 Redis 会话映射，Redis 状态丢失后仍有效")
        void jwt_survives_redis_state_loss() {
            String token = authPort.login(7003);
            assertEquals(7003, authPort.resolve(token).orElseThrow());

            // JWT 的 token 格式是自包含的（三段式），服务端没有 auth:token: 映射
            assertTrue(token.chars().filter(c -> c == '.').count() == 2,
                    "JWT 应为三段式自包含 token");
            assertTrue(redis.keys("auth:token:*").stream().noneMatch(k -> k.contains(token)),
                    "JWT 模式不应写入会话映射");

            // 与 Session 的对照：Redis 没有会话映射，JWT 依靠 MySQL 撤销真值。
            assertEquals(7003, authPort.resolve(token).orElseThrow(),
                    "JWT 不依赖 Redis 会话映射，仍可完成鉴权");
        }

        @Test
        @DisplayName("JWT：签名仍合法，但登出后的会话被拒绝")
        void jwt_logout_revokes_database_session() {
            String token = authPort.login(7004);

            // 登出前：有效
            assertEquals(7004, authPort.resolve(token).orElseThrow());

            // 登出撤销整个 sid，不需要另存每个 access token 的 jti。
            authPort.logout(token);
            assertTrue(authPort.resolve(token).isEmpty(),
                    "会话撤销后必须失效");

            // 同一个 token 的签名和有效期依然合法，失效来自服务端会话状态。
            String[] parts = token.split("\\.");
            assertEquals(3, parts.length, "被吊销的 JWT 结构上依然完整合法");
        }

        @Test
        @DisplayName("JWT：伪造签名的 token 被拒绝")
        void jwt_rejects_forged_signature() {
            String token = authPort.login(7005);
            // 篡改 payload 段（改 userId），签名不再匹配
            String[] parts = token.split("\\.");
            String forged = parts[0] + "." + parts[1] + "x." + parts[2];
            assertTrue(authPort.resolve(forged).isEmpty(), "伪造 token 必须被拒绝");
        }
    }
}
