package com.peergrab.worker;

import com.peergrab.application.usecase.query.BloomRebuildUseCase;
import com.peergrab.infrastructure.cache.RedisErrandCacheAdapter;
import com.peergrab.infrastructure.cache.RedissonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.ServerSocket;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BloomRebuildOfflineStartupTest {

    @Test
    void worker_cache_rebuild_does_not_block_startup_without_redis() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", unavailablePort);
        factory.afterPropertiesSet();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("offline-redis",
                    Map.of("spring.data.redis.host", "127.0.0.1", "spring.data.redis.port", unavailablePort,
                            "peergrab.cache.bloom-enabled", true)));
            context.register(RedissonConfig.class, RedisErrandCacheAdapter.class, BloomRebuildJob.class);
            context.registerBean(StringRedisTemplate.class, () -> new StringRedisTemplate(factory));
            context.registerBean(BloomRebuildUseCase.class, () -> mock(BloomRebuildUseCase.class));
            assertDoesNotThrow(context::refresh);

            BloomRebuildJob job = context.getBean(BloomRebuildJob.class);
            assertDoesNotThrow(job::ensureReady);
            assertTrue(context.getBean(RedisErrandCacheAdapter.class).isDegraded());
            assertFalse(context.getBeanFactory().containsSingleton("redissonClient"));
        } finally {
            factory.destroy();
        }
    }
}
