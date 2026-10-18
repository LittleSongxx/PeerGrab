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

    @Bean("mqConsumerScheduler")
    public ThreadPoolTaskScheduler mqConsumerScheduler() {
        return scheduler("peergrab-mq-connect-", 2);
    }

    /**
     * Broker 故障时同步 send 可能长时间阻塞；MQ 发送任务必须与超时/结算扫描分池，
     * 否则"主通道故障"会连带拖垮兜底扫描。
     */
    @Bean("mqSendScheduler")
    public ThreadPoolTaskScheduler mqSendScheduler(
            @Value("${peergrab.scheduler.mq-send-pool-size:2}") int poolSize) {
        return scheduler("peergrab-mq-send-", poolSize);
    }

    private ThreadPoolTaskScheduler scheduler(String prefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, poolSize));
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
