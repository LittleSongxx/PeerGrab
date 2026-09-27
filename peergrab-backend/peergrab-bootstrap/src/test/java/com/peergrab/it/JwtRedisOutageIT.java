package com.peergrab.it;

import com.peergrab.domain.auth.ports.RefreshTokenPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Redis 整体不可达时，生产 JWT 鉴权、刷新和登出仍以 MySQL 真值工作。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"peergrab.auth.mode=jwt", "peergrab.auth.allow-header-identity=false",
                "peergrab.mq.enabled=false", "peergrab.cache.enabled=false", "peergrab.ws.enabled=false",
                "spring.data.redis.port=1"})
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "peergrab.it", matches = "true")
class JwtRedisOutageIT {

    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired RefreshTokenPort tokens;

    @SuppressWarnings("unchecked")
    private Map<String, Object> successfulData(Map<?, ?> body) {
        assertNotNull(body);
        assertEquals("OK", body.get("code"), String.valueOf(body));
        return (Map<String, Object>) body.get("data");
    }

    @Test
    void protected_request_refresh_and_logout_work_without_redis() {
        var login = successfulData(rest.postForEntity("/api/auth/login",
                Map.of("userId", 1001L, "password", "integration-publisher-password"), Map.class).getBody());
        String access = String.valueOf(login.get("accessToken"));
        String refresh = String.valueOf(login.get("refreshToken"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_token WHERE token_hash = ?", Integer.class, refresh),
                "数据库不能存 refresh token 明文");

        assertEquals(200, rest.exchange("/api/wallet", HttpMethod.GET,
                bearer(access), Map.class).getStatusCode().value());
        var rotated = successfulData(rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refresh), Map.class).getBody());
        String nextAccess = String.valueOf(rotated.get("accessToken"));
        String nextRefresh = String.valueOf(rotated.get("refreshToken"));
        assertEquals("UNAUTHORIZED", rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", refresh), Map.class).getBody().get("code"));
        assertEquals(200, rest.exchange("/api/wallet", HttpMethod.GET,
                bearer(nextAccess), Map.class).getStatusCode().value());

        var logout = rest.exchange("/api/auth/logout", HttpMethod.POST,
                new HttpEntity<>(Map.of("refreshToken", nextRefresh), bearerHeaders(nextAccess)), Map.class);
        assertEquals("OK", logout.getBody().get("code"));
        assertEquals(401, rest.exchange("/api/wallet", HttpMethod.GET,
                bearer(nextAccess), Map.class).getStatusCode().value());
        assertEquals("UNAUTHORIZED", rest.postForEntity("/api/auth/refresh",
                Map.of("refreshToken", nextRefresh), Map.class).getBody().get("code"));
        assertTrue(jdbc.queryForObject("""
                SELECT COUNT(*) FROM auth_token_session
                 WHERE user_id = 1001 AND revoked_at IS NOT NULL
                """, Integer.class) > 0);
    }

    @Test
    void refresh_only_logout_revokes_its_access_session() {
        var login = successfulData(rest.postForEntity("/api/auth/login",
                Map.of("userId", 1001L, "password", "integration-publisher-password"), Map.class).getBody());
        String access = String.valueOf(login.get("accessToken"));
        String refresh = String.valueOf(login.get("refreshToken"));

        assertEquals("OK", rest.postForEntity("/api/auth/logout",
                Map.of("refreshToken", refresh), Map.class).getBody().get("code"));
        assertEquals(401, rest.exchange("/api/wallet", HttpMethod.GET,
                bearer(access), Map.class).getStatusCode().value());
    }

    @Test
    void concurrent_refresh_can_rotate_only_once_and_access_logout_revokes_refresh() throws Exception {
        var pair = tokens.loginWithRefresh(8101L);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return tokens.refresh(pair.refreshToken());
            });
            var second = pool.submit(() -> {
                start.await();
                return tokens.refresh(pair.refreshToken());
            });
            start.countDown();
            var a = first.get(10, TimeUnit.SECONDS);
            var b = second.get(10, TimeUnit.SECONDS);
            assertEquals(1, (a.isPresent() ? 1 : 0) + (b.isPresent() ? 1 : 0));
            var rotated = a.orElseGet(b::orElseThrow);
            tokens.logout(rotated.accessToken());
            assertTrue(tokens.resolve(rotated.accessToken()).isEmpty());
            assertTrue(tokens.refresh(rotated.refreshToken()).isEmpty());
        }
    }

    private static HttpEntity<Void> bearer(String token) {
        return new HttpEntity<>(bearerHeaders(token));
    }

    private static HttpHeaders bearerHeaders(String token) {
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
