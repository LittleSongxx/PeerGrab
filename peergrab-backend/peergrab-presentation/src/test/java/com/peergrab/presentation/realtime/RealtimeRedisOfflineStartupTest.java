package com.peergrab.presentation.realtime;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

class RealtimeRedisOfflineStartupTest {

    @Test
    void websocket_fanout_listener_does_not_prevent_context_start_when_redis_is_down() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6399);
        factory.afterPropertiesSet();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(RealtimeRedisConfig.class);
            context.registerBean(RedisConnectionFactory.class, () -> factory);
            context.registerBean(RealtimePushService.class, () -> mock(RealtimePushService.class));
            assertDoesNotThrow(context::refresh);
        } finally {
            factory.destroy();
        }
    }
}
