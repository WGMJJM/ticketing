-- 调用方持 MySQL SKU 行锁；暂停新受理，待已有预扣结算完才重置绝对余额。
if ARGV[1] == 'pause' then
    if redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
    redis.call('HSET', KEYS[1], 'ready', '0')
    return tonumber(redis.call('HGET', KEYS[1], 'pending') or '-1')
end
local exists = redis.call('EXISTS', KEYS[1]) == 1
if not exists and ARGV[1] ~= 'initialize' then return -1 end
if exists and tonumber(redis.call('HGET', KEYS[1], 'pending') or '-1') ~= 0 then return 0 end
if redis.call('EXISTS', KEYS[2]) == 0 then
    if ARGV[9] ~= '1' then return -1 end
    redis.call('SET', KEYS[2], '0')
end
redis.call('HSET', KEYS[1], 'available', ARGV[2], 'eligible', ARGV[3],
  'saleStartsAt', ARGV[4], 'saleEndsAt', ARGV[5], 'maxQuantity', ARGV[6],
  'sessionId', ARGV[7], 'inventorySequence', ARGV[8], 'pending', '0', 'ready', '1', 'waitlistPending', '0', 'waitlistPriority', ARGV[10])
return 1
