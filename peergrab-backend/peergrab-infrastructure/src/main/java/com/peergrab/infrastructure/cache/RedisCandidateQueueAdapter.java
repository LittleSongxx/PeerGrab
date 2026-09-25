package com.peergrab.infrastructure.cache;

import com.peergrab.domain.grab.ports.CandidateQueuePort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 候选队列的 Redis ZSET 实现。
 * score 越小越优先：score = 抢单时间戳 - 信用分加权，
 * 让高信用用户在同等手速下更容易接到流转的单，但加权有上限，避免高信用用户垄断。
 */
@Component
public class RedisCandidateQueueAdapter implements CandidateQueuePort {

    private static final RedisScript<Long> OFFER_SCRIPT = offerScript();

    private final StringRedisTemplate redis;
    private final long ttlSeconds;

    public RedisCandidateQueueAdapter(StringRedisTemplate redis,
                                      @Value("${peergrab.candidate.ttl-seconds:86400}") long ttlSeconds) {
        this.redis = redis;
        this.ttlSeconds = Math.max(1, ttlSeconds);
    }

    @Override
    public void offer(long errandId, long runnerId, double score) {
        redis.execute(OFFER_SCRIPT, List.of(key(errandId)),
                String.valueOf(score), String.valueOf(runnerId), String.valueOf(ttlSeconds));
    }

    @Override
    public Optional<Candidate> pollBest(long errandId) {
        // ZPOPMIN 是单条原子命令，多消费者不能弹出同一位候选人。
        ZSetOperations.TypedTuple<String> popped = redis.opsForZSet().popMin(key(errandId));
        if (popped == null || popped.getValue() == null) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(Long.parseLong(popped.getValue()),
                popped.getScore() == null ? 0.0 : popped.getScore()));
    }

    @Override
    public long size(long errandId) {
        Long n = redis.opsForZSet().zCard(key(errandId));
        return n == null ? 0L : n;
    }

    @Override
    public void clear(long errandId) {
        redis.delete(key(errandId));
    }

    private String key(long errandId) {
        return "errand:candidates:{" + errandId + "}";
    }

    private static RedisScript<Long> offerScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/candidate_offer.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
