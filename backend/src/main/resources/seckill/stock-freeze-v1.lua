-- KEYS: skuHash, frozenSkuZset, fenceCounter
-- ARGV: skuId, owner, leaseMs, nowMs
local state = redis.call('HGET', KEYS[1], 'state')
local untilMs = tonumber(redis.call('HGET', KEYS[1], 'freezeUntil') or '0')
if state == 'FROZEN' and untilMs > tonumber(ARGV[4]) then return {0, 0} end
local fence = redis.call('INCR', KEYS[3])
redis.call('HSET', KEYS[1], 'state', 'FROZEN', 'freezeOwner', ARGV[2], 'freezeFence', fence,
  'freezeUntil', tonumber(ARGV[4]) + tonumber(ARGV[3]))
redis.call('ZADD', KEYS[2], tonumber(ARGV[4]) + tonumber(ARGV[3]), ARGV[1])
return {1, fence}
