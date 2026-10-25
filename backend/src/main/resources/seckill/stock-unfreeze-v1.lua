-- KEYS: skuHash, frozenSkuZset
-- ARGV: skuId, owner, fence, eligible, expectedMutation
if redis.call('HGET', KEYS[1], 'state') ~= 'FROZEN'
  or redis.call('HGET', KEYS[1], 'freezeOwner') ~= ARGV[2]
  or redis.call('HGET', KEYS[1], 'freezeFence') ~= ARGV[3] then return 0 end
if (redis.call('HGET', KEYS[1], 'mutation') or '0') ~= ARGV[5] then return -1 end
local nextState = ARGV[4] == '1' and 'ACTIVE' or 'PAUSED'
redis.call('HSET', KEYS[1], 'state', nextState, 'eligible', ARGV[4], 'freezeUntil', 0, 'freezeOwner', '')
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
