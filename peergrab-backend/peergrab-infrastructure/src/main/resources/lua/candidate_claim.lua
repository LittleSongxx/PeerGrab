-- 原子恢复到期租约并领取最优候选；候选在确认前不会永久删除。
-- KEYS: available ZSET, claims ZSET(expiry millis), scores HASH, tokens HASH
-- ARGV: lease millis, claim token, ttl seconds
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local expired = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', now, 'LIMIT', 0, 1000)
for _, member in ipairs(expired) do
    local score = redis.call('HGET', KEYS[3], member)
    redis.call('ZREM', KEYS[2], member)
    redis.call('HDEL', KEYS[3], member)
    redis.call('HDEL', KEYS[4], member)
    if score then
        redis.call('ZADD', KEYS[1], 'NX', score, member)
    end
end

local candidate = redis.call('ZPOPMIN', KEYS[1])
if #candidate == 0 then
    return {}
end
local member = candidate[1]
local score = candidate[2]
redis.call('ZADD', KEYS[2], now + tonumber(ARGV[1]), member)
redis.call('HSET', KEYS[3], member, score)
redis.call('HSET', KEYS[4], member, ARGV[2])
for _, key in ipairs(KEYS) do
    redis.call('EXPIRE', key, tonumber(ARGV[3]))
end
return {member, score, ARGV[2]}
