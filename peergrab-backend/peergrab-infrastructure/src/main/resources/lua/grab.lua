-- 抢单原子判定脚本
--
-- 为什么必须用 Lua：Redis 单条命令是原子的，但多条命令之间会被其他客户端插入。
-- "GET 名额 -> 判断 > 0 -> DECR" 这个序列在并发下必然出错（两个客户端都读到 1）。
-- Lua 脚本在 Redis 中作为一个整体执行，中途不会被打断。
--
-- KEYS[1] = errand:slot:{taskId}      剩余名额（string）
-- KEYS[2] = errand:grabbed:{taskId}   已抢中用户集合（set）
-- KEYS[3] = errand:idem:{taskId}:requestId 幂等键（值为 runnerId）
-- KEYS[4] = errand:pending:{taskId}    短期 DB 落库窗口标记
-- ARGV[1] = runnerId
-- ARGV[2] = 幂等键过期秒数
-- ARGV[3] = 预占位窗口秒数
-- 返回：1=占位成功 0=名额已满 -1=用户已抢过 -2=同用户重复请求
--       -4=名额键丢失，走 DB CAS；-5=同一 requestId 被另一用户占用

-- 幂等：同一 requestId 重复提交直接返回上次结果，不重复扣名额
local previousRunner = redis.call('GET', KEYS[3])
if previousRunner then
    if previousRunner == ARGV[1] then
        return -2
    end
    return -5
end

local slot = redis.call('GET', KEYS[1])
if not slot then
    -- 缓存丢失与任务不可抢不能混为一谈，应用层须交给 DB CAS 最终裁决。
    return -4
end

-- INV-2：同一用户不能占两个名额
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- INV-1：名额守恒
if tonumber(slot) <= 0 then
    return 0
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
local slotTtl = redis.call('TTL', KEYS[1])
if slotTtl > 0 then
    redis.call('EXPIRE', KEYS[2], slotTtl)
end
redis.call('SETEX', KEYS[3], tonumber(ARGV[2]), ARGV[1])
redis.call('SETEX', KEYS[4], tonumber(ARGV[3]), ARGV[1])
return 1
