-- 原子入队并设置生命周期。重复抢单不改变第一次排队的分数。
-- KEYS[1] = errand:candidates:{errandId}
-- ARGV[1] = score, ARGV[2] = runnerId, ARGV[3] = TTL 秒数
redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return redis.call('ZCARD', KEYS[1])
