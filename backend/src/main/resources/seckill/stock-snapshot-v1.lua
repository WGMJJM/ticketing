-- KEYS: skuHash
-- ARGV: owner, fence, available, version, eligible, expectedMutation
if redis.call('HGET', KEYS[1], 'state') ~= 'FROZEN'
  or redis.call('HGET', KEYS[1], 'freezeOwner') ~= ARGV[1]
  or redis.call('HGET', KEYS[1], 'freezeFence') ~= ARGV[2] then return 0 end
if (redis.call('HGET', KEYS[1], 'mutation') or '0') ~= ARGV[6] then return -1 end
redis.call('HSET', KEYS[1], 'available', ARGV[3], 'version', ARGV[4], 'eligible', ARGV[5])
return 1
