package com.peergrab.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 到期处理与维护重建分池，长时间对账不会阻塞消息重发和超时兜底。 */
@Configuration
public class WorkerSchedulers {

    @Bean("fastTaskScheduler")
    public ThreadPoolTaskScheduler fastTaskScheduler(
            @Value("${peergrab.scheduler.fast-pool-size:3}") int poolSize) {
        return scheduler("peergrab-fast-", poolSize);
    }

    @Bean("maintenanceTaskScheduler")
    public ThreadPoolTaskScheduler maintenanceTaskScheduler(
            @Value("${peergrab.scheduler.maintenance-pool-size:3}") int poolSize) {
        return scheduler("peergrab-maintenance-", poolSize);
    }

    private ThreadPoolTaskScheduler scheduler(String prefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
