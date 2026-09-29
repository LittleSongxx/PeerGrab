package com.peergrab.application.usecase;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Fixed-cardinality timings for the stages of a grab request after authentication. */
@Component
public class GrabPhaseMetrics {

    public enum Stage {
        RATE_LIMIT("rate_limit"),
        TASK_READ("task_read"),
        REPLAY_READ("replay_read"),
        CREDIT_READ("credit_read"),
        ONGOING_READ("ongoing_read"),
        SLOT_RESERVE("slot_reserve"),
        SLOT_ROLLBACK("slot_rollback"),
        SLOT_PENDING("slot_pending"),
        DB_TRANSACTION("db_transaction"),
        CANDIDATE_OFFER("candidate_offer"),
        POST_COMMIT("post_commit");

        private final String label;

        Stage(String label) {
            this.label = label;
        }
    }

    private final EnumMap<Stage, Timer> timers = new EnumMap<>(Stage.class);

    public GrabPhaseMetrics(MeterRegistry registry) {
        for (Stage stage : Stage.values()) {
            timers.put(stage, Timer.builder("peergrab.grab.phase")
                    .description("Grab use case stage duration; excludes authentication and servlet queueing")
                    .tag("stage", stage.label)
                    .publishPercentileHistogram()
                    .register(registry));
        }
    }

    public <T> T time(Stage stage, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            timers.get(stage).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    public void time(Stage stage, Runnable action) {
        time(stage, () -> {
            action.run();
            return null;
        });
    }
}
