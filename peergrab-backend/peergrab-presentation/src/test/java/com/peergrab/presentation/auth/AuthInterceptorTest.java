package com.peergrab.presentation.auth;

import com.peergrab.domain.auth.ports.AuthPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthInterceptorTest {

    @Test
    void records_bearer_resolution_without_changing_authentication_result() {
        AuthPort auth = mock(AuthPort.class);
        when(auth.resolve("good")).thenReturn(Optional.of(42L));
        when(auth.resolve("bad")).thenReturn(Optional.empty());

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        AuthInterceptor interceptor = new AuthInterceptor(auth, false, meters);
        MockHttpServletRequest accepted = new MockHttpServletRequest();
        accepted.addHeader("Authorization", "Bearer good");
        assertTrue(interceptor.preHandle(accepted, new MockHttpServletResponse(), new Object()));
        assertEquals(42L, accepted.getAttribute(CurrentUser.ATTR));

        MockHttpServletRequest rejected = new MockHttpServletRequest();
        rejected.addHeader("Authorization", "Bearer bad");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(rejected, response, new Object()));
        assertEquals(401, response.getStatus());

        assertEquals(1, meters.get("peergrab.auth.resolve").tag("outcome", "accepted").timer().count());
        assertEquals(1, meters.get("peergrab.auth.resolve").tag("outcome", "rejected").timer().count());
        assertTrue(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("good") || tag.getValue().contains("bad")));
    }
}
