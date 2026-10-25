-- KEYS: skuHash, tokenHash, skuTokensZset, allTokensZset, orphanRecoveryZset, pendingCounter
-- ARGV: token, terminalState (CONSUMED or RELEASED)
local state = redis.call('HGET', KEYS[2], 'state')
if not state then return {0, 0} end
if state == ARGV[2] then return {0, 0} end
if state ~= 'RESERVED' then return {-1, 0} end
local quantity = tonumber(redis.call('HGET', KEYS[2], 'quantity') or '0')
if quantity < 1 then return {-2, 0} end
local pending = tonumber(redis.call('GET', KEYS[6]) or '-1')
if pending < 1 then return {-3, 0} end
redis.call('HSET', KEYS[2], 'state', ARGV[2], 'recoveryUntil', 0, 'recoveryOwner', '')
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('ZREM', KEYS[5], ARGV[1])
redis.call('DECR', KEYS[6])
if ARGV[2] == 'RELEASED' and redis.call('HGET', KEYS[1], 'state') == 'ACTIVE' then
  redis.call('HINCRBY', KEYS[1], 'available', quantity)
end
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
return {1, quantity}
