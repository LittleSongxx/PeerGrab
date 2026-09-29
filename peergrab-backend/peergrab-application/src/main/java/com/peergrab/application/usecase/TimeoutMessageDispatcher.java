package com.peergrab.application.usecase;

import com.peergrab.domain.errand.ports.DelayMessagePort;
import com.peergrab.domain.errand.ports.LocalMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends committed timeout messages without making a successful grab wait for RocketMQ.
 * Every submitted message already has a PENDING local_message row. Queue rejection,
 * process exit, send failure, or a failed SENT update leave that row for the worker's
 * claim-and-retry job; the database timeout scanner also covers missed delivery.
 */
@Component
public class TimeoutMessageDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TimeoutMessageDispatcher.class);

    private final DelayMessagePort delayMessagePort;
    private final LocalMessageRepository localMessageRepository;
    private final ThreadPoolExecutor sender;
    private final Counter failed;
    private final Counter deferred;
    private final AtomicLong deferredCount = new AtomicLong();

    public TimeoutMessageDispatcher(DelayMessagePort delayMessagePort,
                                    LocalMessageRepository localMessageRepository,
                                    MeterRegistry registry,
                                    @Value("${peergrab.timeout.dispatch-sender-threads:4}") int threads,
                                    @Value("${peergrab.timeout.dispatch-queue-capacity:2048}") int queueCapacity) {
        if (threads < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("timeout dispatch threads and queue capacity must be positive");
        }
        this.delayMessagePort = delayMessagePort;
        this.localMessageRepository = localMessageRepository;
        this.sender = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), task -> {
                    Thread thread = new Thread(task, "timeout-message-mq");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.failed = Counter.builder("peergrab.timeout.dispatch.failed")
                .description("Committed timeout messages whose immediate MQ send or SENT update failed")
                .register(registry);
        this.deferred = Counter.builder("peergrab.timeout.dispatch.deferred")
                .description("Committed timeout messages deferred to the durable worker retry path")
                .register(registry);
        Gauge.builder("peergrab.timeout.dispatch.queue.depth", sender,
                        executor -> executor.getQueue().size())
                .description("Committed timeout messages waiting for an immediate MQ send")
                .register(registry);
    }

    public void dispatch(TimeoutTransferStep.PendingSend send) {
        if (send == null) return;
        try {
            if (!delayMessagePort.available()) return;
            sender.execute(() -> sendAndMark(send));
        } catch (RejectedExecutionException e) {
            defer(send.msgKey(), "the sender queue is full or stopped");
        } catch (RuntimeException e) {
            // The PENDING row is already committed. An availability-check failure
            // must not change the API result after the grab transaction committed.
            defer(send.msgKey(), "the MQ availability check failed");
            log.warn("MQ availability check failed msgKey={}", send.msgKey(), e);
        }
    }

    private void sendAndMark(TimeoutTransferStep.PendingSend send) {
        try {
            delayMessagePort.send(send.topic(), send.msgKey(), send.payload(), send.deliverAt());
            localMessageRepository.markSent(send.msgKey());
        } catch (RuntimeException e) {
            // If send succeeded but marking SENT failed, a later retry may duplicate
            // the message. The consumer's status/round/version checks make it safe.
            failed.increment();
            log.warn("超时消息即时投递或标记 SENT 失败，留待 worker 重发 msgKey={}", send.msgKey(), e);
        }
    }

    private void defer(String msgKey, String reason) {
        deferred.increment();
        long total = deferredCount.incrementAndGet();
        if (total == 1 || (total & (total - 1)) == 0) {
            log.warn("超时消息即时投递已延后，留待 worker 重发 msgKey={} reason={} 累计={}",
                    msgKey, reason, total);
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    public void stop() {
        // Never wait for a paused Broker during shutdown. Every queued message
        // remains PENDING in MySQL and will be reclaimed by the worker.
        int abandoned = sender.shutdownNow().size();
        if (abandoned > 0) {
            deferred.increment(abandoned);
            deferredCount.addAndGet(abandoned);
            log.warn("停机时放弃 {} 条即时投递任务，留待 worker 重发", abandoned);
        }
    }
}
