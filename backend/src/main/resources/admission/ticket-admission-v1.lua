-- v1 单Redis实例双令牌桶，TIME为时钟，单位为毫令牌，拒绝不写任何桶。
local clock = redis.call('TIME')
local now = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
local rates = {tonumber(ARGV[1]), tonumber(ARGV[3])}
local bursts = {tonumber(ARGV[2]), tonumber(ARGV[4])}
local tokens = {}
local timestamps = {}
for i = 1, 2 do
    local old = redis.call('HMGET', KEYS[i], 'tokens', 'time')
    local cap = bursts[i] * 1000
    local previous = tonumber(old[1]) or cap
    local at = tonumber(old[2]) or now
    timestamps[i] = math.max(now, at)
    tokens[i] = math.min(cap, previous + math.max(0, now - at) * rates[i])
end
-- 先完成两个维度的判断，票档拒绝不会提前扣减全局桶。
for i = 1, 2 do
    if tokens[i] < 1000 then
        return {i, math.ceil((1000 - tokens[i]) / rates[i])}
    end
end
for i = 1, 2 do
    redis.call('HSET', KEYS[i], 'tokens', tokens[i] - 1000, 'time', timestamps[i])
    -- 空闲满桶后即可销毁，拒绝请求不延长寿命、不创建任意新票档键。
    redis.call('PEXPIRE', KEYS[i], math.ceil(bursts[i] * 1000 / rates[i]) + 1000)
end
return {0, 0}
