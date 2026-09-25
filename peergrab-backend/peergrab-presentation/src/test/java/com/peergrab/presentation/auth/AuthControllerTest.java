package com.peergrab.presentation.auth;

import com.peergrab.domain.auth.ports.RefreshTokenPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AuthControllerTest {

    @Test
    @DisplayName("JWT refresh token 刷新后轮换，旧 token 复用被拒绝")
    void refresh_rotates_token_and_rejects_replay() {
        FakeRefreshAuthPort authPort = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(authPort,
                policy(true));

        var login = controller.login(new AuthController.LoginRequest(1001L, "test-publisher-password")).data();
        assertEquals(login.get("token"), login.get("accessToken"));
        String refreshToken = String.valueOf(login.get("refreshToken"));

        var refreshed = controller.refresh(new AuthController.RefreshRequest(refreshToken));
        assertEquals("OK", refreshed.code());
        String nextRefreshToken = String.valueOf(refreshed.data().get("refreshToken"));
        assertNotEquals(refreshToken, nextRefreshToken);

        var replay = controller.refresh(new AuthController.RefreshRequest(refreshToken));
        assertEquals("UNAUTHORIZED", replay.code());
    }

    @Test
    @DisplayName("演示登录默认关闭，启用时也拒绝空密码、未知身份及普通密码冒充仲裁员")
    void demo_login_requires_explicit_credentials_and_allowed_id() {
        FakeRefreshAuthPort authPort = new FakeRefreshAuthPort();
        AuthController disabled = new AuthController(authPort,
                new DemoLoginPolicy(false, "", "", "", "", 9001L));
        assertEquals("UNAUTHORIZED", disabled.login(new AuthController.LoginRequest(1001L, "anything")).code());

        AuthController enabled = new AuthController(authPort, policy(true));
        assertEquals("UNAUTHORIZED", enabled.login(new AuthController.LoginRequest(1001L, null)).code());
        assertEquals("UNAUTHORIZED", enabled.login(new AuthController.LoginRequest(30000L, "test-publisher-password")).code());
        assertEquals("UNAUTHORIZED", enabled.login(new AuthController.LoginRequest(2001L, "test-publisher-password")).code());
        assertEquals("UNAUTHORIZED", enabled.login(new AuthController.LoginRequest(9001L, "test-publisher-password")).code());
        assertEquals("OK", enabled.login(new AuthController.LoginRequest(9001L, "test-arbitrator-password")).code());
    }

    @Test
    @DisplayName("登出时吊销 refresh token")
    void logout_revokes_refresh_token() {
        FakeRefreshAuthPort authPort = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(authPort, policy(true));
        var login = controller.login(new AuthController.LoginRequest(1001L, "test-publisher-password")).data();
        String refreshToken = String.valueOf(login.get("refreshToken"));

        controller.logout("Bearer " + login.get("accessToken"),
                new AuthController.LogoutRequest(refreshToken));

        assertEquals("UNAUTHORIZED", controller.refresh(new AuthController.RefreshRequest(refreshToken)).code());
    }

    @Test
    @DisplayName("access 失效后仍可凭 refresh token 登出；无凭据无法登出")
    void logout_accepts_valid_refresh_without_access() {
        FakeRefreshAuthPort authPort = new FakeRefreshAuthPort();
        AuthController controller = new AuthController(authPort, policy(true));
        var login = controller.login(new AuthController.LoginRequest(1001L, "test-publisher-password")).data();
        String refreshToken = String.valueOf(login.get("refreshToken"));

        assertEquals("UNAUTHORIZED", controller.logout(null, null).code());
        assertEquals("OK", controller.logout(null, new AuthController.LogoutRequest(refreshToken)).code());
        assertEquals("UNAUTHORIZED", controller.refresh(new AuthController.RefreshRequest(refreshToken)).code());
    }

    private DemoLoginPolicy policy(boolean enabled) {
        return new DemoLoginPolicy(enabled, "test-publisher-password", "test-runner-one-password",
                "test-runner-two-password", "test-arbitrator-password", 9001L);
    }

    private static final class FakeRefreshAuthPort implements RefreshTokenPort {
        private final Map<String, Long> refreshTokens = new HashMap<>();
        private int accessSeq;
        private int refreshSeq;

        @Override
        public TokenPair loginWithRefresh(long userId) {
            return new TokenPair(issueAccess(userId), issueRefresh(userId));
        }

        @Override
        public Optional<TokenPair> refresh(String refreshToken) {
            Long userId = refreshTokens.remove(refreshToken);
            if (userId == null) {
                return Optional.empty();
            }
            return Optional.of(new TokenPair(issueAccess(userId), issueRefresh(userId)));
        }

        private String issueAccess(long userId) {
            return "access-" + userId + "-" + (++accessSeq);
        }

        private String issueRefresh(long userId) {
            String token = "refresh-" + userId + "-" + (++refreshSeq);
            refreshTokens.put(token, userId);
            return token;
        }

        @Override public String login(long userId) { return issueAccess(userId); }
        @Override public Optional<Long> resolve(String token) { return Optional.empty(); }
        @Override public void logout(String token) {}
        @Override public boolean revokeRefresh(String refreshToken) { return refreshTokens.remove(refreshToken) != null; }
    }
}
