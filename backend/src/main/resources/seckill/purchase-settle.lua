-- 成功凭据永不因订单取消而重置；只从 RESERVED 迁移一次。
if redis.call('EXISTS', KEYS[2]) == 0 or redis.call('EXISTS', KEYS[1]) == 0 then return -1 end
local current = redis.call('HGET', KEYS[2], 'state')
local next = ARGV[2] == 'SUCCEEDED' and 'CONSUMED' or 'RELEASED'
if current ~= 'RESERVED' then
    if current == next and redis.call('HGET', KEYS[2], 'status') == ARGV[2] then return 0 end
    return -1
end
local quantity = tonumber(redis.call('HGET', KEYS[2], 'quantity'))
if tonumber(redis.call('GET', KEYS[5]) or '0') < 1
    or tonumber(redis.call('HGET', KEYS[1], 'pending') or '0') < 1
    or tonumber(redis.call('GET', KEYS[7]) or '0') < quantity then return -1 end
local waitlist = redis.call('HGET', KEYS[2], 'operation') == 'WAITLIST_CONFIRM'
if waitlist and tonumber(redis.call('HGET', KEYS[1], 'waitlistPending') or '0') < 1 then return -1 end
if next == 'RELEASED' then redis.call('HINCRBY', KEYS[1], 'available', quantity) end
redis.call('HINCRBY', KEYS[1], 'pending', -1)
if waitlist then redis.call('HINCRBY', KEYS[1], 'waitlistPending', -1) end
redis.call('DECR', KEYS[5])
if redis.call('DECRBY', KEYS[7], quantity) == 0 then redis.call('DEL', KEYS[7]) end
for _, key in ipairs({KEYS[2], KEYS[3]}) do
    redis.call('HSET', key, 'state', next, 'status', ARGV[2], 'orderId', ARGV[3], 'failureCode', ARGV[4])
end
redis.call('ZREM', KEYS[4], ARGV[6])
redis.call('ZREM', KEYS[6], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[5])
return 1
