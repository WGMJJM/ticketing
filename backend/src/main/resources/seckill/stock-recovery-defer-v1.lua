-- KEYS: tokenHash, reservationRecoveryZset
-- ARGV: token, nextCheckMs
if redis.call('HGET', KEYS[1], 'state') ~= 'RESERVED' then return 0 end
redis.call('HSET', KEYS[1], 'recoveryOwner', '', 'recoveryUntil', 0)
redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
return 1
