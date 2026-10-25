-- KEYS: pendingCounter, initLock
-- ARGV: owner, leaseMs
if redis.call('GET', KEYS[1]) then return 0 end
local acquired = redis.call('SET', KEYS[2], ARGV[1], 'NX', 'PX', ARGV[2])
if acquired then return 1 end
return 2
