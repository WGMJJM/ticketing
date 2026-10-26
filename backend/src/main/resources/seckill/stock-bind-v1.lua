-- KEYS: tokenHash, reservationRecoveryZset
-- ARGV: token, requestId, nowMs, retryMs
if redis.call('HGET', KEYS[1], 'state') ~= 'RESERVED' then return 0 end
local requestId = redis.call('HGET', KEYS[1], 'requestId') or ''
if requestId ~= '' and requestId ~= ARGV[2] then return -1 end
redis.call('HSET', KEYS[1], 'requestId', ARGV[2], 'recoveryOwner', '', 'recoveryUntil', 0)
redis.call('ZADD', KEYS[2], tonumber(ARGV[3]) + tonumber(ARGV[4]), ARGV[1])
return 1
