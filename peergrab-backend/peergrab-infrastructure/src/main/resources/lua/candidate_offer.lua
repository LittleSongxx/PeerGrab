-- 原子入队并设置生命周期。重复抢单不改变第一次排队的分数。
-- KEYS[1] = errand:candidates:{errandId}
-- KEYS[4] = 当前租约 token 哈希；已被领取的候选人不能重复入队。
-- ARGV[1] = score, ARGV[2] = runnerId, ARGV[3] = TTL 秒数
if redis.call('HEXISTS', KEYS[4], ARGV[2]) == 0 then
    redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
end
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return redis.call('ZCARD', KEYS[1]) + redis.call('ZCARD', KEYS[2])
