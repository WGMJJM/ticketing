-- KEYS: skuHash, tokenHash, skuTokensZset, reservationRecoveryZset, pendingCounter
-- ARGV: token, userId, skuId, quantity, idempotencyKey, requestHashHex, maxPending, recoveryDelayMs
local now = redis.call('TIME')
local nowMs = now[1] * 1000 + math.floor(now[2] / 1000)
local tokenState = redis.call('HGET', KEYS[2], 'state')
if tokenState then
  if redis.call('HGET', KEYS[2], 'userId') ~= ARGV[2]
    or redis.call('HGET', KEYS[2], 'skuId') ~= ARGV[3]
    or redis.call('HGET', KEYS[2], 'quantity') ~= ARGV[4]
    or redis.call('HGET', KEYS[2], 'requestHash') ~= ARGV[6]
    or redis.call('HGET', KEYS[2], 'idempotencyKey') ~= ARGV[5] then return {-6, 0} end
  if tokenState == 'CONSUMED' or tokenState == 'RELEASED' then return {-8, 0} end
  if tokenState ~= 'RESERVED' then return {-6, 0} end
  local recoveryUntil = tonumber(redis.call('HGET', KEYS[2], 'recoveryUntil') or '0')
  local recoveryOwner = redis.call('HGET', KEYS[2], 'recoveryOwner') or ''
  if recoveryOwner ~= '' and recoveryUntil > nowMs then return {-7, recoveryUntil - nowMs} end
  if (redis.call('HGET', KEYS[2], 'requestId') or '') ~= '' then return {2, 0} end
  local retryAt = nowMs + tonumber(ARGV[8])
  redis.call('HSET', KEYS[2], 'recoveryUntil', retryAt, 'recoveryOwner', '')
  redis.call('ZADD', KEYS[4], retryAt, ARGV[1])
  return {2, 0}
end

local state = redis.call('HGET', KEYS[1], 'state')
local version = redis.call('HGET', KEYS[1], 'version')
local startsAt = tonumber(redis.call('HGET', KEYS[1], 'saleStartsAt'))
local endsAt = tonumber(redis.call('HGET', KEYS[1], 'saleEndsAt'))
if not state or not version or not string.match(version, '^%d+$') or not startsAt or not endsAt then return {-3, 0} end
if state == 'FROZEN' then return {-7, 1000} end
if state ~= 'ACTIVE' or redis.call('HGET', KEYS[1], 'eligible') ~= '1'
  or nowMs < startsAt or nowMs >= endsAt then return {-10, 0} end
local pending = redis.call('GET', KEYS[5])
if not pending or not string.match(pending, '^%d+$') then return {-5, 0} end
if tonumber(pending) >= tonumber(ARGV[7]) then return {-2, 0} end
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '-1')
local quantity = tonumber(ARGV[4])
if available < 0 then return {-9, 0} end
if available < quantity then return {-1, 0} end

local recoveryAt = nowMs + tonumber(ARGV[8])
redis.call('HINCRBY', KEYS[1], 'available', -quantity)
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
redis.call('HSET', KEYS[2], 'state', 'RESERVED', 'userId', ARGV[2], 'skuId', ARGV[3],
  'quantity', ARGV[4], 'idempotencyKey', ARGV[5], 'requestHash', ARGV[6],
  'recoveryUntil', recoveryAt, 'recoveryOwner', '', 'requestId', '')
redis.call('INCR', KEYS[5])
redis.call('ZADD', KEYS[3], nowMs, ARGV[1])
redis.call('ZADD', KEYS[4], recoveryAt, ARGV[1])
return {1, 0}
