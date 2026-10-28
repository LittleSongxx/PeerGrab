package com.peergrab.presentation.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部运维端点的第二道凭证。
 *
 * <p>内部端点原本只要求“仲裁员 Bearer token”，但仲裁员 token 一旦泄露，
 * 缓存统计、缓存校验、预热门这些高消耗操作就会直接暴露给公网入口。
 * 这里增加可选的 {@code X-Internal-Token}：
 * <ul>
 *   <li>未配置（空字符串）：不启用第二道校验，保持本地/CI/历史压测客户端兼容；</li>
 *   <li>已配置：必须与 {@code peergrab.auth.internal-token} 常量时间相等，长度不足 32 字节直接启动失败；</li>
 *   <li>比较使用 {@link MessageDigest#isEqual}，避免逐字符提前返回的时序侧信道。</li>
 * </ul>
 * 生产部署应同时依赖 Nginx 对 {@code /api/internal/} 的 404 与这里配置的 token。</p>
 */
@Component
public class InternalAuthInterceptor implements HandlerInterceptor {

    private final String internalToken;
    private final Counter rejected;

    public InternalAuthInterceptor(@Value("${peergrab.auth.internal-token:}") String internalToken,
                                   MeterRegistry registry) {
        this.internalToken = internalToken == null ? "" : internalToken.trim();
        if (!this.internalToken.isEmpty() && this.internalToken.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("peergrab.auth.internal-token 至少需要 32 字节；空值表示不启用");
        }
        this.rejected = registry.counter("peergrab.internal.auth", "outcome", "rejected");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (internalToken.isEmpty()) {
            return true;
        }
        String provided = request.getHeader("X-Internal-Token");
        if (provided != null && constantTimeEquals(internalToken, provided.trim())) {
            return true;
        }
        rejected.increment();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /** 供配置守卫/测试使用。 */
    public boolean enabled() {
        return !internalToken.isEmpty();
    }
}
