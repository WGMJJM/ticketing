-- KEYS: skuHash, tokenHash, skuTokensZset, allTokensZset, orphanRecoveryZset, pendingCounter
-- ARGV: token, owner
if redis.call('HGET', KEYS[2], 'state') ~= 'RESERVED'
  or redis.call('HGET', KEYS[2], 'recoveryOwner') ~= ARGV[2] then return 0 end
local quantity = tonumber(redis.call('HGET', KEYS[2], 'quantity') or '0')
local pending = tonumber(redis.call('GET', KEYS[6]) or '-1')
if quantity < 1 or pending < 1 then return -1 end
redis.call('HSET', KEYS[2], 'state', 'RELEASED', 'recoveryOwner', '', 'recoveryUntil', 0)
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[1])
redis.call('DECR', KEYS[6])
if redis.call('HGET', KEYS[1], 'state') == 'ACTIVE' then
  redis.call('HINCRBY', KEYS[1], 'available', quantity)
end
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
return 1
