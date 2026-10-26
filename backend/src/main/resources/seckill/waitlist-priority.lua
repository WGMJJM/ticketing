-- 调用者持有同一 MySQL SKU 协调锁；普通预扣与开启/关闭在 Redis 中原子互斥。
if redis.call('EXISTS', KEYS[1]) == 0
    or not redis.call('HGET', KEYS[1], 'waitlistPriority') then return -1 end
if ARGV[1] == 'join' then
    if redis.call('HGET', KEYS[1], 'ready') ~= '1' then return -1 end
    if ARGV[3] ~= '1' and tonumber(redis.call('HGET', KEYS[1], 'available')) >= tonumber(ARGV[2]) then return -2 end
    redis.call('HSET', KEYS[1], 'waitlistPriority', '1')
elseif ARGV[1] == 'select' then
    if redis.call('HGET', KEYS[1], 'waitlistHead') ~= ARGV[2] then
        if tonumber(redis.call('HGET', KEYS[1], 'waitlistPending') or '-1') ~= 0 then return 0 end
        redis.call('HSET', KEYS[1], 'waitlistRequest', '')
    end
    redis.call('HSET', KEYS[1], 'waitlistPriority', '1', 'waitlistHead', ARGV[2])
elseif ARGV[1] == 'finish' then
    if redis.call('HGET', KEYS[1], 'waitlistHead') ~= ARGV[2]
        or tonumber(redis.call('HGET', KEYS[1], 'waitlistPending') or '-1') ~= 0 then return 0 end
    -- 先使尚在事务外执行的旧尝试失效，再允许 SQL 推进代数；防止无票拒绝和重投交叉。
    redis.call('HSET', KEYS[1], 'waitlistHead', '', 'waitlistRequest', '')
elseif ARGV[1] == 'close' then
    if tonumber(redis.call('HGET', KEYS[1], 'waitlistPending') or '-1') ~= 0 then return 0 end
    redis.call('HSET', KEYS[1], 'waitlistPriority', '0', 'waitlistHead', '')
else return -1 end
return 1
