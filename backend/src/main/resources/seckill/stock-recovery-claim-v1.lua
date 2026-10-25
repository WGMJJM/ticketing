-- KEYS: tokenHash, orphanRecoveryZset
-- ARGV: token, owner, nowMs, leaseMs
local due = redis.call('ZSCORE', KEYS[2], ARGV[1])
if not due or tonumber(due) > tonumber(ARGV[3]) then return 0 end
local recoveryUntil = tonumber(redis.call('HGET', KEYS[1], 'recoveryUntil') or '0')
if recoveryUntil > tonumber(ARGV[3]) then return 0 end
if redis.call('HGET', KEYS[1], 'state') ~= 'RESERVED' then
  redis.call('ZREM', KEYS[2], ARGV[1])
  return 0
end
local leaseUntil = tonumber(ARGV[3]) + tonumber(ARGV[4])
redis.call('HSET', KEYS[1], 'recoveryOwner', ARGV[2], 'recoveryUntil', leaseUntil)
redis.call('ZADD', KEYS[2], leaseUntil, ARGV[1])
return 1
