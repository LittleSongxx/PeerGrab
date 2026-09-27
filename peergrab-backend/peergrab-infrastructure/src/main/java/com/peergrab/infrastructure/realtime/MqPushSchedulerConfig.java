package com.peergrab.infrastructure.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** MQ 建连重试与雪花租约续期隔离，避免 broker 超时阻塞发号安全检查。 */
@Configuration
@ConditionalOnProperty(name = {"peergrab.mq.enabled", "peergrab.ws.enabled"}, havingValue = "true")
public class MqPushSchedulerConfig {

    @Bean("mqConsumerScheduler")
    public ThreadPoolTaskScheduler mqConsumerScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("mq-push-connect-");
        return scheduler;
    }
}
