-- 调用方持 MySQL SKU 行锁；暂停新受理，待已有预扣结算完才重置绝对余额。
if ARGV[1] == 'pause' then
    if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
    redis.call('HSET', KEYS[1], 'ready', '0')
    return tonumber(redis.call('HGET', KEYS[1], 'pending') or '-1')
end
local exists = redis.call('EXISTS', KEYS[1]) == 1
if not exists and ARGV[1] ~= 'initialize' then return -1 end
if exists then
    local currentVersion = redis.call('HGET', KEYS[1], 'inventoryVersion')
        or redis.call('HGET', KEYS[1], 'inventorySequence')
    for _, field in ipairs({'available', 'eligible', 'saleStartsAt', 'saleEndsAt', 'maxQuantity',
            'sessionId', 'pending', 'waitlistPending', 'waitlistPriority'}) do
        if not redis.call('HGET', KEYS[1], field) then return -1 end
    end
    if not currentVersion or not string.match(currentVersion, '^%d+$')
        or not string.match(ARGV[8], '^%d+$')
        or not tonumber(redis.call('HGET', KEYS[1], 'available'))
        or tonumber(redis.call('HGET', KEYS[1], 'available')) < 0 then return -1 end
    local pendingCount = tonumber(redis.call('HGET', KEYS[1], 'pending'))
    local waitlistPending = tonumber(redis.call('HGET', KEYS[1], 'waitlistPending'))
    local priority = redis.call('HGET', KEYS[1], 'waitlistPriority')
    local eligible = redis.call('HGET', KEYS[1], 'eligible')
    if not pendingCount or pendingCount < 0 or not waitlistPending or waitlistPending < 0
        or waitlistPending > pendingCount or (priority ~= '0' and priority ~= '1')
        or (eligible ~= '0' and eligible ~= '1') then return -1 end
    if pendingCount ~= 0 then return 0 end
    -- 十进制字符串比较避免 Lua 数值精度丢失，旧快照不能覆盖新版本。
    local current = string.gsub(currentVersion, '^0+', '')
    local target = string.gsub(ARGV[8], '^0+', '')
    if #current > #target or (#current == #target and current > target) then return -2 end
end
if redis.call('EXISTS', KEYS[2]) == 0 then
    if ARGV[9] ~= '1' then return -1 end
    redis.call('SET', KEYS[2], '0')
end
redis.call('HSET', KEYS[1], 'available', ARGV[2], 'eligible', ARGV[3],
  'saleStartsAt', ARGV[4], 'saleEndsAt', ARGV[5], 'maxQuantity', ARGV[6],
  'sessionId', ARGV[7], 'inventoryVersion', ARGV[8], 'pending', '0', 'ready', '1', 'waitlistPending', '0', 'waitlistPriority', ARGV[10])
redis.call('HDEL', KEYS[1], 'inventorySequence')
return 1
