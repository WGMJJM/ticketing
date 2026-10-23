-- 首次放行开启固定窗口；全部维度同时通过才递增，拒绝不延长正常窗口。
local retry = 0
for i, key in ipairs(KEYS) do
    local count = redis.call('GET', key)
    local ttl = redis.call('PTTL', key)
    if count and ttl < 0 then
        -- 恢复被外部误写为永久键的计数，避免形成永久封禁。
        ttl = tonumber(ARGV[i * 2])
        redis.call('PEXPIRE', key, ttl)
    end
    if count and tonumber(count) >= tonumber(ARGV[i * 2 - 1]) then
        retry = math.max(retry, math.max(ttl, 1))
    end
end
if retry > 0 then return {0, retry} end
for i, key in ipairs(KEYS) do
    local count = redis.call('INCR', key)
    if count == 1 then redis.call('PEXPIRE', key, ARGV[i * 2]) end
end
return {1, 0}
