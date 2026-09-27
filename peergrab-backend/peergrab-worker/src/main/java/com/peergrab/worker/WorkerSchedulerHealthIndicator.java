package com.peergrab.worker;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/** Reports a stalled fast scheduler even while the worker JVM remains alive. */
@Component
public class WorkerSchedulerHealthIndicator implements HealthIndicator {

    private final AtomicReference<Instant> lastTick = new AtomicReference<>(Instant.now());

    @Scheduled(fixedDelay = 5000, scheduler = "fastTaskScheduler")
    public void tick() {
        lastTick.set(Instant.now());
    }

    @Override
    public Health health() {
        Instant last = lastTick.get();
        long ageSeconds = Duration.between(last, Instant.now()).toSeconds();
        return ageSeconds > 30
                ? Health.down().withDetail("lastTick", last).withDetail("ageSeconds", ageSeconds).build()
                : Health.up().withDetail("lastTick", last).build();
    }
}
