package com.peergrab.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedissonOfflineStartupTest {

    @Test
    void cache_context_starts_without_redis_and_read_degrades_without_creating_redisson() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", unavailablePort);
        factory.afterPropertiesSet();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("offline-redis",
                    Map.of("spring.data.redis.host", "127.0.0.1", "spring.data.redis.port", unavailablePort)));
            context.register(RedissonConfig.class, RedisErrandCacheAdapter.class);
            context.registerBean(StringRedisTemplate.class, () -> new StringRedisTemplate(factory));
            assertDoesNotThrow(context::refresh);
            assertFalse(context.getBeanFactory().containsSingleton("redissonClient"));

            RedisErrandCacheAdapter cache = context.getBean(RedisErrandCacheAdapter.class);
            assertTrue(cache.get(42).isEmpty());
            assertTrue(cache.isDegraded());
            assertTrue(cache.mightExist(42));
            assertFalse(context.getBeanFactory().containsSingleton("redissonClient"));
        } finally {
            factory.destroy();
        }
    }
}
