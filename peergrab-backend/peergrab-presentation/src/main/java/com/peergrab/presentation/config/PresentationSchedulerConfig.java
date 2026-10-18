package com.peergrab.presentation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * API 侧实时相关定时任务线程池。
 *
 * <p>WebSocket 会话复核与 Redis 订阅重连此前共用 Spring 默认的单线程调度器，
 * 任一任务变慢都会拖住另一个。拆池后互不影响。</p>
 */
@Configuration
public class PresentationSchedulerConfig {

    @Bean("realtimeTaskScheduler")
    public ThreadPoolTaskScheduler realtimeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("peergrab-realtime-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
