-- KEYS: skuHash, tokenHash, skuTokensZset, allTokensZset, reservationRecoveryZset
-- ARGV: token, userId, skuId, quantity, idempotencyKey, requestHashHex, requestId, nextCheckMs
local state = redis.call('HGET', KEYS[2], 'state')
if state and (state ~= 'RESERVED' or redis.call('HGET', KEYS[2], 'userId') ~= ARGV[2]) then return -1 end
redis.call('HSET', KEYS[2], 'state', 'RESERVED', 'userId', ARGV[2], 'skuId', ARGV[3],
  'quantity', ARGV[4], 'idempotencyKey', ARGV[5], 'requestHash', ARGV[6], 'requestId', ARGV[7],
  'recoveryOwner', '', 'recoveryUntil', 0)
redis.call('ZADD', KEYS[3], 0, ARGV[1])
redis.call('ZADD', KEYS[4], 0, ARGV[1])
redis.call('ZADD', KEYS[5], tonumber(ARGV[8]), ARGV[1])
redis.call('HINCRBY', KEYS[1], 'mutation', 1)
return 1
