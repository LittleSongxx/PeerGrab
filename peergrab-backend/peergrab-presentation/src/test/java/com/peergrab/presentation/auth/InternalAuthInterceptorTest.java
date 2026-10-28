package com.peergrab.presentation.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/** 内部端点的第二凭证：未配置时兼容、配置后必须常量时间匹配。 */
class InternalAuthInterceptorTest {

    private static final String TOKEN = "internal-token-0123456789abcdef-0123456789";

    @Test
    @DisplayName("未配置 token 时不启用第二道校验（本地/CI 兼容）")
    void disabled_when_blank() {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor("", new SimpleMeterRegistry());
        assertFalse(interceptor.enabled());
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("配置后缺少或错误 X-Internal-Token 返回 401")
    void rejects_missing_or_wrong_token() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor(TOKEN, meters);

        MockHttpServletResponse missing = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(new MockHttpServletRequest(), missing, new Object()));
        assertEquals(401, missing.getStatus());

        MockHttpServletRequest wrongRequest = new MockHttpServletRequest();
        wrongRequest.addHeader("X-Internal-Token", "wrong");
        MockHttpServletResponse wrong = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(wrongRequest, wrong, new Object()));
        assertEquals(401, wrong.getStatus());
        assertEquals(2, meters.get("peergrab.internal.auth").tag("outcome", "rejected").counter().count());
    }

    @Test
    @DisplayName("正确 token 放行")
    void allows_correct_token() {
        InternalAuthInterceptor interceptor = new InternalAuthInterceptor(TOKEN, new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    @DisplayName("配置过短的 token 直接拒绝启动")
    void rejects_short_token_configuration() {
        assertThrows(IllegalStateException.class,
                () -> new InternalAuthInterceptor("too-short", new SimpleMeterRegistry()));
    }
}
