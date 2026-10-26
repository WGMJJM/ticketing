-- KEYS: skuHash, tokenHash, skuTokensZset, reservationRecoveryZset, pendingCounter
-- ARGV: token, terminalState, retentionSeconds, skuId, optionalRecoveryOwner
local state = redis.call('HGET', KEYS[2], 'state')
if not state then return {0, 0} end
if redis.call('HGET', KEYS[2], 'skuId') ~= ARGV[4] then return {-4, 0} end
if state == 'CONSUMED' or state == 'RELEASED' then
  -- 兼容旧的无TTL终态；重复结算不延长已设置的保留期。
  if redis.call('TTL', KEYS[2]) < 0 then redis.call('EXPIRE', KEYS[2], ARGV[3]) end
  return {0, 0}
end
if state ~= 'RESERVED' then return {-1, 0} end
if ARGV[5] ~= '' and redis.call('HGET', KEYS[2], 'recoveryOwner') ~= ARGV[5] then return {-5, 0} end
local quantity = tonumber(redis.call('HGET', KEYS[2], 'quantity') or '0')
if quantity < 1 then return {-2, 0} end
local pending = tonumber(redis.call('GET', KEYS[5]) or '-1')
if pending < 1 then return {-3, 0} end
local target = ARGV[2]
-- 旧终态token到期后，迟到HTTP可能重新预扣；未绑定申请的新预扣必须退回，不能再次消费。
if target == 'CONSUMED' and (redis.call('HGET', KEYS[2], 'requestId') or '') == '' then target = 'RELEASED' end
redis.call('HSET', KEYS[2], 'state', target, 'recoveryUntil', 0, 'recoveryOwner', '')
redis.call('ZREM', KEYS[3], ARGV[1])
redis.call('ZREM', KEYS[4], ARGV[1])
redis.call('DECR', KEYS[5])
if target == 'RELEASED' and redis.call('HGET', KEYS[1], 'state') == 'ACTIVE' then
  redis.call('HINCRBY', KEYS[1], 'available', quantity)
end
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
redis.call('EXPIRE', KEYS[2], ARGV[3])
return {1, quantity}
