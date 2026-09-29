package com.peergrab.infrastructure.cache;

import com.peergrab.domain.errand.ports.CacheEvictDelayPort;
import com.peergrab.shared.MessagePayloadCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 延迟双删的 RocketMQ 定时消息实现。
 *
 * 首次删缓存在提交后由调用方立即执行；这里的 MQ 延迟双删只是加固。
 * Broker 暂停时 Producer.send 可能等待客户端超时，因此必须在有界后台队列中调用，
 * 不能把已提交的写请求继续挂在发送结果上。进程退出或队列满可能丢掉第二次删除，
 * 缓存 TTL 和抽样一致性校验负责兜底，不能把本队列当成可靠消息表。
 */
@Component
@ConditionalOnProperty(name = "peergrab.mq.enabled", havingValue = "true")
public class RocketMqCacheEvictAdapter implements CacheEvictDelayPort {

    private static final Logger log = LoggerFactory.getLogger(RocketMqCacheEvictAdapter.class);

    private final Producer producer;
    private final ClientServiceProvider provider;
    private final ThreadPoolExecutor sender;
    private final Counter sendFailures;
    private final Counter dropped;
    private final AtomicLong droppedCount = new AtomicLong();

    public RocketMqCacheEvictAdapter(Producer timeoutProducer,
                                    ClientServiceProvider provider,
                                    MeterRegistry registry,
                                    @Value("${peergrab.cache.double-delete-sender-threads:2}") int threads,
                                    @Value("${peergrab.cache.double-delete-queue-capacity:2048}") int queueCapacity) {
        this.producer = timeoutProducer;
        this.provider = provider;
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("double-delete sender threads and queue capacity must be positive");
        }
        this.sender = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "cache-double-delete-mq");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.sendFailures = Counter.builder("peergrab.cache.double_delete.send.failed")
                .description("Delayed cache invalidation MQ sends that failed")
                .register(registry);
        this.dropped = Counter.builder("peergrab.cache.double_delete.dropped")
                .description("Delayed cache invalidations dropped because the sender queue was full or stopped")
                .register(registry);
        Gauge.builder("peergrab.cache.double_delete.queue.depth", sender,
                        executor -> executor.getQueue().size())
                .description("Delayed cache invalidations waiting for MQ send")
                .register(registry);
    }

    @Override
    public void scheduleEvict(long errandId, Instant deliverAt) {
        try {
            sender.execute(() -> send(errandId, deliverAt));
        } catch (RejectedExecutionException e) {
            dropped.increment();
            long total = droppedCount.incrementAndGet();
            // Broker 长时间故障时避免在请求线程逐条打印告警，精确数量由 Counter 记录。
            if (total == 1 || (total & (total - 1)) == 0) {
                log.warn("延迟双删队列已满或停止，放弃第二次删除 errandId={}，累计丢弃={}，依赖 TTL 与一致性校验兜底",
                        errandId, total);
            }
        }
    }

    private void send(long errandId, Instant deliverAt) {
        try {
            // 排队期间原时间戳可能过期。重新约定稍后的投递时间，避免 Broker 拒绝过期定时消息。
            long now = System.currentTimeMillis();
            long deliveryTimestamp = deliverAt.toEpochMilli() > now + 100L
                    ? deliverAt.toEpochMilli() : now + 2_000L;
            Message msg = provider.newMessageBuilder()
                    .setTopic(CacheEvictDelayPort.TOPIC_CACHE_EVICT)
                    .setKeys("evict:" + errandId)
                    .setBody(MessagePayloadCodec.cacheEvict(errandId).getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(deliveryTimestamp)
                    .build();
            producer.send(msg);
        } catch (Exception e) {
            sendFailures.increment();
            log.warn("延迟双删消息发送失败 errandId={}", errandId, e);
        }
    }

    @PreDestroy
    public void stop() {
        // 不在停机阶段等待 Broker 的请求超时；未投递的双删由 TTL 与抽样校验兜底。
        int abandoned = sender.shutdownNow().size();
        if (abandoned > 0) {
            dropped.increment(abandoned);
            droppedCount.addAndGet(abandoned);
            log.warn("停机时放弃 {} 条排队中的延迟双删，依赖 TTL 与一致性校验兜底", abandoned);
        }
    }
}
