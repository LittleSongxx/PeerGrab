package com.peergrab.presentation.auth;

import com.peergrab.domain.auth.ports.AuthPort;
import com.peergrab.domain.auth.ports.RefreshTokenPort;
import com.peergrab.shared.ErrorCode;
import com.peergrab.shared.Result;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录 / 刷新 / 登出。登录与刷新不走 AuthInterceptor（登录时还没有 token，刷新时 access token 可能已过期），
 * 由 WebConfig 的 excludePathPatterns 排除。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthPort authPort;
    private final DemoLoginPolicy loginPolicy;

    public AuthController(AuthPort authPort, DemoLoginPolicy loginPolicy) {
        this.authPort = authPort;
        this.loginPolicy = loginPolicy;
    }

    public record LoginRequest(Long userId, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record LogoutRequest(String refreshToken) {}

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest req) {
        if (!loginPolicy.allows(req.userId(), req.password())) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        if (authPort instanceof RefreshTokenPort refreshTokenPort) {
            var pair = refreshTokenPort.loginWithRefresh(req.userId());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("token", pair.accessToken());       // 兼容旧前端
            data.put("accessToken", pair.accessToken());
            data.put("refreshToken", pair.refreshToken());
            data.put("userId", req.userId());
            return Result.ok(data);
        }
        String token = authPort.login(req.userId());
        return Result.ok(Map.of("token", token, "userId", req.userId()));
    }

    @PostMapping("/refresh")
    public Result<Map<String, Object>> refresh(@RequestBody RefreshRequest req) {
        if (!(authPort instanceof RefreshTokenPort refreshTokenPort)) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        return refreshTokenPort.refresh(req.refreshToken())
                .map(pair -> {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("token", pair.accessToken());       // 兼容旧前端
                    data.put("accessToken", pair.accessToken());
                    data.put("refreshToken", pair.refreshToken());
                    return Result.ok(data);
                })
                .orElseGet(() -> Result.fail(ErrorCode.UNAUTHORIZED));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String auth,
                               @RequestBody(required = false) LogoutRequest req) {
        String accessToken = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        boolean validAccess = accessToken != null && authPort.resolve(accessToken).isPresent();
        boolean validRefresh = false;
        if (req != null && authPort instanceof RefreshTokenPort refreshTokenPort) {
            validRefresh = refreshTokenPort.revokeRefresh(req.refreshToken());
        }
        if (!validAccess && !validRefresh) {
            return Result.fail(ErrorCode.UNAUTHORIZED);
        }
        if (validAccess) {
            authPort.logout(accessToken);
        }
        return Result.ok(null);
    }
}
