-- 只有当前租约 token 能确认删除，防止超时旧 worker 删除重新领取的候选。
-- KEYS: available ZSET, claims ZSET, scores HASH, tokens HASH
-- ARGV: runnerId, claim token
if redis.call('HGET', KEYS[4], ARGV[1]) ~= ARGV[2] then
    return 0
end
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('HDEL', KEYS[3], ARGV[1])
redis.call('HDEL', KEYS[4], ARGV[1])
return 1
