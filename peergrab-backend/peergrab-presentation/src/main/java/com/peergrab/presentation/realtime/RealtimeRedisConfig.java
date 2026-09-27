package com.peergrab.presentation.realtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fan out best-effort realtime events to every API replica holding WebSocket sessions. */
@Configuration
@ConditionalOnProperty(name = "peergrab.ws.enabled", havingValue = "true", matchIfMissing = true)
public class RealtimeRedisConfig {

    @Bean
    public RedisMessageListenerContainer realtimeMessageListenerContainer(
            RedisConnectionFactory connectionFactory, RealtimePushService pushService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(pushService, new ChannelTopic(RealtimePushService.CHANNEL));
        // Starting a listener connects immediately; Redis downtime must not prevent
        // the API from starting when MySQL-backed authentication is available.
        return container;
    }

    @Bean
    public SubscriberStarter realtimeSubscriberStarter(RedisMessageListenerContainer container) {
        return new SubscriberStarter(container);
    }

    public static final class SubscriberStarter {
        private static final Logger log = LoggerFactory.getLogger(SubscriberStarter.class);
        private final RedisMessageListenerContainer container;

        private SubscriberStarter(RedisMessageListenerContainer container) {
            this.container = container;
        }

        @Scheduled(initialDelay = 1000, fixedDelay = 10000)
        public void reconnect() {
            if (container.isRunning()) return;
            try {
                container.start();
            } catch (RuntimeException e) {
                log.warn("Redis 实时订阅不可用，将继续重试；客户端仍定期轮询", e);
            }
        }
    }
}
