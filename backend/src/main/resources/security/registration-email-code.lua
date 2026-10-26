-- KEYS: purpose/email digest key; ARGV: operation, sendId/digest, digest/ttlSeconds, ttlSeconds/maxAttempts
if ARGV[1] == 'prepare' then
  if redis.call('HGET', KEYS[1], 'state') == 'SENDING' then return 0 end
  redis.call('HSET', KEYS[1], 'state', 'SENDING', 'sendId', ARGV[2], 'digest', ARGV[3], 'attempts', 0)
  redis.call('EXPIRE', KEYS[1], ARGV[4])
  return 1
end
if ARGV[1] == 'activate' or ARGV[1] == 'abort' then
  if redis.call('HGET', KEYS[1], 'sendId') ~= ARGV[2] then return 0 end
  if ARGV[1] == 'abort' then return redis.call('DEL', KEYS[1]) end
  if redis.call('HGET', KEYS[1], 'state') ~= 'SENDING' then return 0 end
  redis.call('HSET', KEYS[1], 'state', 'ACTIVE')
  redis.call('EXPIRE', KEYS[1], ARGV[3])
  return 1
end
if ARGV[1] == 'consume' then
  if redis.call('HGET', KEYS[1], 'state') ~= 'ACTIVE' then return 0 end
  local attempts = tonumber(redis.call('HGET', KEYS[1], 'attempts') or '0')
  if attempts >= tonumber(ARGV[4]) then return -2 end
  if redis.call('HGET', KEYS[1], 'digest') ~= ARGV[2] then
    attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
    if attempts >= tonumber(ARGV[4]) then return -2 end
    return -1
  end
  redis.call('DEL', KEYS[1])
  return 1
end
return 0
