package com.peergrab.bench;

import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 隔离压测栈上的 RocketMQ 定时消息连通性探针。
 * 独占 topic 和 consumer group，绝不能加入业务消费者组并确认业务消息。
 *
 * 用法：java -cp ... DelayMessageProbe [endpoint] [delaySeconds]
 */
public class DelayMessageProbe {

    private static final String TOPIC = "peergrab-bench-delay-probe";
    private static final String GROUP = "peergrab-bench-delay-probe-consumer";

    public static void main(String[] args) throws Exception {
        String endpoint = args.length > 0 ? args[0]
                : "127.0.0.1:" + System.getenv("PEERGRAB_TEST_MQ_PORT");
        int delaySeconds = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        if (delaySeconds < 1 || delaySeconds > 3600) {
            throw new IllegalArgumentException("delaySeconds must be 1..3600");
        }
        BenchSafety.requireDisposableStack();
        BenchSafety.requireBenchmarkMqEndpoint(endpoint);

        ClientServiceProvider provider = ClientServiceProvider.loadService();
        ClientConfiguration config = ClientConfiguration.newBuilder()
                .setEndpoints(endpoint)
                .setRequestTimeout(Duration.ofSeconds(10))
                .build();

        String probeKey = "probe-" + System.currentTimeMillis();
        CountDownLatch received = new CountDownLatch(1);
        long[] receivedAt = new long[1];

        try (PushConsumer consumer = provider.newPushConsumerBuilder()
                .setClientConfiguration(config)
                .setConsumerGroup(GROUP)
                .setSubscriptionExpressions(Collections.singletonMap(
                        TOPIC, new FilterExpression("*", FilterExpressionType.TAG)))
                .setMessageListener(msg -> {
                    String keys = msg.getKeys().isEmpty() ? "" : msg.getKeys().iterator().next();
                    if (probeKey.equals(keys)) {
                        receivedAt[0] = System.currentTimeMillis();
                        received.countDown();
                    }
                    // 独占探针 topic；历史探针消息可以安全 ACK。
                    return ConsumeResult.SUCCESS;
                })
                .build()) {

            long expectAt = System.currentTimeMillis() + delaySeconds * 1000L;
            try (Producer producer = provider.newProducerBuilder()
                    .setClientConfiguration(config)
                    .setTopics(TOPIC)
                    .build()) {
                Message message = provider.newMessageBuilder()
                        .setTopic(TOPIC)
                        .setKeys(probeKey)
                        .setBody(("{\"probe\":\"" + probeKey + "\"}").getBytes(StandardCharsets.UTF_8))
                        // 5.x 的任意时间定时消息：这是选 5.x 客户端的唯一理由，
                        // 4.x 只有 18 个固定 delay level，做不到"5 分钟后精确投递"
                        .setDeliveryTimestamp(expectAt)
                        .build();
                var receipt = producer.send(message);
                System.out.printf("[probe] 已发送定时消息 msgId=%s 期望投递=%d(+%ds)%n",
                        receipt.getMessageId(), expectAt, delaySeconds);
            }

            boolean ok = received.await(delaySeconds + 30L, TimeUnit.SECONDS);
            if (!ok) {
                System.err.println("[FAIL] 超时未收到定时消息，MQ 链路不通");
                System.exit(1);
            }
            long errorMs = receivedAt[0] - expectAt;
            System.out.printf("[probe] 已收到！实际投递=%d 误差=%dms%n", receivedAt[0], errorMs);
            if (Math.abs(errorMs) > 3000) {
                System.err.printf("[WARN] 投递误差 %dms 偏大，S5 准时率可能不达标%n", errorMs);
            }
            System.out.println("[PASS] RocketMQ 定时消息链路可用");
        }
    }
}
