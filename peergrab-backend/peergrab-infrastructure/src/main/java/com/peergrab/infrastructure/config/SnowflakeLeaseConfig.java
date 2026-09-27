package com.peergrab.infrastructure.config;

import com.peergrab.shared.SnowflakeIdGenerator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class SnowflakeLeaseConfig {

    @Bean
    public JdbcSnowflakeNodeLease jdbcSnowflakeNodeLease(JdbcTemplate jdbc,
            @Value("${peergrab.worker-id:1}") int preferredNodeId,
            @Value("${peergrab.worker-lease.seconds:30}") int leaseSeconds,
            @Value("${peergrab.worker-lease.close-on-loss:false}") boolean closeOnLoss,
            ConfigurableApplicationContext context) {
        JdbcSnowflakeNodeLease lease = new JdbcSnowflakeNodeLease(jdbc, preferredNodeId, leaseSeconds);
        if (closeOnLoss) {
            // Fail closed immediately, then let Compose restart with a fresh safe lease.
            // Closing on a separate thread avoids joining the scheduler from itself.
            lease.onLeaseLost(() -> new Thread(context::close, "snowflake-lease-shutdown").start());
        }
        return lease;
    }

    @Bean("snowflakeLeaseHealthIndicator")
    public HealthIndicator snowflakeLeaseHealthIndicator(JdbcSnowflakeNodeLease lease) {
        return () -> lease.canGenerateIds() ? Health.up().build() : Health.down().build();
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator(JdbcSnowflakeNodeLease lease) {
        return lease.generator();
    }

    @Bean("snowflakeLeaseScheduler")
    public ThreadPoolTaskScheduler snowflakeLeaseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("snowflake-lease-");
        return scheduler;
    }
}
