-- KEYS: skuHash, tokenHash, skuTokensZset, allTokensZset, orphanRecoveryZset, pendingCounter
-- ARGV: token, userId, skuId, quantity, idempotencyKey, requestHashHex, skuVersion, maxPending, recoveryDelayMs
local now = redis.call('TIME')
local nowMs = now[1] * 1000 + math.floor(now[2] / 1000)
local state = redis.call('HGET', KEYS[1], 'state')
local version = redis.call('HGET', KEYS[1], 'version')
local eligible = redis.call('HGET', KEYS[1], 'eligible')
if state ~= 'ACTIVE' or eligible ~= '1' then return {-3, 0} end
if version ~= ARGV[7] then return {-4, 0} end
local pending = redis.call('GET', KEYS[6])
if not pending or not string.match(pending, '^%d+$') then return {-5, 0} end

local tokenState = redis.call('HGET', KEYS[2], 'state')
if tokenState then
  if redis.call('HGET', KEYS[2], 'userId') ~= ARGV[2]
    or redis.call('HGET', KEYS[2], 'skuId') ~= ARGV[3]
    or redis.call('HGET', KEYS[2], 'quantity') ~= ARGV[4]
    or redis.call('HGET', KEYS[2], 'requestHash') ~= ARGV[6]
    or redis.call('HGET', KEYS[2], 'idempotencyKey') ~= ARGV[5] then return {-6, 0} end
  local recoveryUntil = tonumber(redis.call('HGET', KEYS[2], 'recoveryUntil') or '0')
  if recoveryUntil > nowMs then return {-7, recoveryUntil - nowMs} end
  if tokenState == 'CONSUMED' or tokenState == 'RELEASED' then return {-8, 0} end
  if tokenState == 'RESERVED' then
    if redis.call('HGET', KEYS[2], 'requestId') then return {2, 0} end
    local retryAt = nowMs + tonumber(ARGV[9])
    redis.call('HSET', KEYS[2], 'recoveryUntil', retryAt, 'recoveryOwner', '')
    redis.call('ZADD', KEYS[5], retryAt, ARGV[1])
    return {2, 0}
  end
end

if tonumber(pending) >= tonumber(ARGV[8]) then return {-2, 0} end
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '-1')
local quantity = tonumber(ARGV[4])
if available < 0 then return {-9, 0} end
if available < quantity then return {-1, 0} end

local recoveryAt = nowMs + tonumber(ARGV[9])
redis.call('HINCRBY', KEYS[1], 'available', -quantity)
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
redis.call('HSET', KEYS[2], 'state', 'RESERVED', 'userId', ARGV[2], 'skuId', ARGV[3],
  'quantity', ARGV[4], 'idempotencyKey', ARGV[5], 'requestHash', ARGV[6],
  'recoveryUntil', recoveryAt, 'recoveryOwner', '', 'requestId', '')
redis.call('INCR', KEYS[6])
redis.call('ZADD', KEYS[3], nowMs, ARGV[1])
redis.call('ZADD', KEYS[4], nowMs, ARGV[1])
redis.call('ZADD', KEYS[5], recoveryAt, ARGV[1])
return {1, 0}
