-- KEYS: skuHash, frozenSkuZset
-- ARGV: owner, fence, available, configVersion, eligible, expectedMutation, saleStartsAt, saleEndsAt, skuId, reopen, inventorySequence
if redis.call('HGET', KEYS[1], 'state') ~= 'FROZEN'
  or redis.call('HGET', KEYS[1], 'freezeOwner') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'freezeFence') ~= ARGV[2] then return 0 end
if (redis.call('HGET', KEYS[1], 'mutation') or '0') ~= ARGV[6] then return -1 end
redis.call('HSET', KEYS[1], 'available', ARGV[3], 'version', ARGV[4], 'eligible', ARGV[5],
  'saleStartsAt', ARGV[7], 'saleEndsAt', ARGV[8], 'inventorySequence', ARGV[11])
if ARGV[10] == '1' then
  local nextState = ARGV[5] == '1' and 'ACTIVE' or 'PAUSED'
  redis.call('HSET', KEYS[1], 'state', nextState, 'freezeUntil', 0, 'freezeOwner', '')
  redis.call('ZREM', KEYS[2], ARGV[9])
end
return 1
