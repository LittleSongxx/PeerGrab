package com.peergrab;

import com.peergrab.application.usecase.CacheEvictSupport;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CacheMetricsConfig {

    @Bean
    public MeterBinder cacheEvictionFailureMetrics(CacheEvictSupport cacheEvict) {
        return registry -> Gauge.builder("peergrab.cache.eviction.failures", cacheEvict,
                        CacheEvictSupport::evictFailureCount)
                .description("Cache invalidations that failed after a committed write")
                .register(registry);
    }
}
