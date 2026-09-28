package com.peergrab.infrastructure.cache;

import com.peergrab.domain.grab.ports.CandidateQueuePort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 候选队列的 Redis ZSET 实现。
 * score 越小越优先：score = 抢单时间戳 - 信用分加权，
 * 让高信用用户在同等手速下更容易接到流转的单，但加权有上限，避免高信用用户垄断。
 */
@Component
public class RedisCandidateQueueAdapter implements CandidateQueuePort {

    private static final RedisScript<Long> OFFER_SCRIPT = offerScript();
    private static final RedisScript<List> CLAIM_SCRIPT = listScript("lua/candidate_claim.lua");
    private static final RedisScript<Long> ACK_SCRIPT = longScript("lua/candidate_ack.lua");
    private static final RedisScript<Long> RELEASE_SCRIPT = longScript("lua/candidate_release.lua");

    private final StringRedisTemplate redis;
    private final long ttlSeconds;
    private final long leaseMillis;

    public RedisCandidateQueueAdapter(StringRedisTemplate redis,
                                      @Value("${peergrab.candidate.ttl-seconds:86400}") long ttlSeconds,
                                      @Value("${peergrab.candidate.lease-seconds:30}") long leaseSeconds) {
        this.redis = redis;
        this.ttlSeconds = Math.max(1, ttlSeconds);
        this.leaseMillis = Math.max(1, leaseSeconds) * 1000;
    }

    @Override
    public long offer(long errandId, long runnerId, double score) {
        Long size = redis.execute(OFFER_SCRIPT, keys(errandId),
                String.valueOf(score), String.valueOf(runnerId), String.valueOf(ttlSeconds));
        if (size == null) {
            throw new IllegalStateException("候选入队脚本未返回队列大小");
        }
        return size;
    }

    @Override
    public Optional<Candidate> pollBest(long errandId) {
        String claimId = UUID.randomUUID().toString();
        List<?> popped = redis.execute(CLAIM_SCRIPT, keys(errandId),
                String.valueOf(leaseMillis), claimId, String.valueOf(ttlSeconds));
        if (popped == null || popped.size() < 3) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(Long.parseLong(popped.get(0).toString()),
                Double.parseDouble(popped.get(1).toString()), popped.get(2).toString()));
    }

    @Override
    public void acknowledge(long errandId, Candidate candidate) {
        redis.execute(ACK_SCRIPT, keys(errandId),
                String.valueOf(candidate.runnerId()), candidate.claimId());
    }

    @Override
    public void release(long errandId, Candidate candidate) {
        redis.execute(RELEASE_SCRIPT, keys(errandId),
                String.valueOf(candidate.runnerId()), candidate.claimId(), String.valueOf(ttlSeconds));
    }

    @Override
    public long size(long errandId) {
        Long available = redis.opsForZSet().zCard(key(errandId));
        Long claimed = redis.opsForZSet().zCard(claimsKey(errandId));
        return (available == null ? 0L : available) + (claimed == null ? 0L : claimed);
    }

    @Override
    public void clear(long errandId) {
        redis.delete(keys(errandId));
    }

    private String key(long errandId) {
        return "errand:candidates:{" + errandId + "}";
    }

    private String claimsKey(long errandId) {
        return key(errandId) + ":claims";
    }

    private List<String> keys(long errandId) {
        String base = key(errandId);
        return List.of(base, base + ":claims", base + ":scores", base + ":tokens");
    }

    private static RedisScript<Long> offerScript() {
        return longScript("lua/candidate_offer.lua");
    }

    private static RedisScript<Long> longScript(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<List> listScript(String path) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(List.class);
        return script;
    }
}
