package com.peergrab.infrastructure.cache;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Redisson 装配。
 *
 * ── 为什么与 Lettuce 并存而不是二选一 ──
 * Lettuce（spring-data-redis）负责普通读写、Lua 脚本和详情重建 token 锁；
 * Redisson 只负责 Lettuce 没有直接端口的布隆过滤器。两个客户端各自连接池，
 * 连接数分开配——叠加打满 Redis 是很现实的风险，所以 Redisson 只给 16 个
 * 连接（它的调用量远小于业务读写）。
 *
 * P1 时刻意没引 Redisson（抢单不需要分布式锁，靠 Lua + DB CAS），
 * 到 P5 才因为布隆过滤器引入；详情重建锁使用原生 SET NX PX，避免为一次冷
 * 回填再支付 Redisson 锁协议的往返。
 * Redisson.create 会建立初始连接；bean 和注入点都需懒加载，使 Redis 故障不阻断启动。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:127.0.0.1}") String host,
            @Value("${spring.data.redis.port:6380}") int port,
            @Value("${peergrab.cache.redisson-pool-size:16}") int poolSize) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionPoolSize(poolSize)
                .setConnectionMinimumIdleSize(Math.max(2, poolSize / 4))
                .setConnectTimeout(500)
                .setRetryAttempts(1)
                .setRetryInterval(250)
                .setTimeout(1000);
        return Redisson.create(config);
    }
}
