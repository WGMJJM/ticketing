-- KEYS: SKU、幂等事实、请求、恢复索引、总排队数、用户未完成申请、场次内用户预扣数。
-- 所有约束先检查；RESERVED 不设置 TTL。
if redis.call('EXISTS', KEYS[2]) == 1 then
    if redis.call('HGET', KEYS[2], 'requestHash') ~= ARGV[6] then return {-6} end
    return {2, redis.call('HGET', KEYS[2], 'id')}
end
if redis.call('HGET', KEYS[1], 'ready') ~= '1' or redis.call('EXISTS', KEYS[5]) == 0 then return {-3} end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if redis.call('HGET', KEYS[1], 'eligible') ~= '1'
    or now < tonumber(redis.call('HGET', KEYS[1], 'saleStartsAt'))
    or now >= tonumber(redis.call('HGET', KEYS[1], 'saleEndsAt')) then return {-10} end
local quantity = tonumber(ARGV[4])
if quantity < 1 or quantity > tonumber(redis.call('HGET', KEYS[1], 'maxQuantity')) then return {-11} end
if tonumber(redis.call('GET', KEYS[7]) or '0') + quantity > tonumber(ARGV[9]) then return {-11} end
if tonumber(redis.call('GET', KEYS[5])) >= tonumber(ARGV[7]) then return {-2} end
if tonumber(redis.call('HGET', KEYS[1], 'available')) < quantity then return {-1} end
if redis.call('EXISTS', KEYS[3]) == 1 then return {-3} end
local deadline = now + tonumber(ARGV[8]) * 1000
for _, key in ipairs({KEYS[2], KEYS[3]}) do
    redis.call('HSET', key, 'id', ARGV[1], 'userId', ARGV[2], 'skuId', ARGV[3],
      'quantity', ARGV[4], 'idempotencyKey', ARGV[5], 'requestHash', ARGV[6],
      'acceptedAt', tostring(now), 'deadlineAt', tostring(deadline), 'status', 'QUEUED',
      'state', 'RESERVED', 'orderId', '', 'failureCode', '', 'sessionId', ARGV[10])
end
redis.call('HINCRBY', KEYS[1], 'available', -quantity)
redis.call('HINCRBY', KEYS[1], 'pending', 1)
redis.call('INCR', KEYS[5])
redis.call('INCRBY', KEYS[7], quantity)
redis.call('ZADD', KEYS[4], now + tonumber(ARGV[11]) * 1000, ARGV[3] .. ':' .. ARGV[1])
redis.call('ZADD', KEYS[6], now, ARGV[1])
return {1, ARGV[1]}
