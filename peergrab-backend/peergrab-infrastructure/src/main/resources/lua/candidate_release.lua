-- CAS 释放租约并按原分数入队；旧 token 不能释放新租约。
-- KEYS: available ZSET, claims ZSET, scores HASH, tokens HASH
-- ARGV: runnerId, claim token, ttl seconds
if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then
    return 0
end
local score = redis.call('HGET', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('HDEL', KEYS[4], ARGV[1])
if score then
    redis.call('ZADD', KEYS[1], 'NX', score, ARGV[1])
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
end
return 1
