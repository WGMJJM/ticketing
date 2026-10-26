-- 幂等摘要保留，详情缓存七天；历史终态回填不增加/释放库存。
if redis.call('HGET', KEYS[1], 'state') == 'RESERVED' then return 0 end
for _, key in ipairs(KEYS) do
    redis.call('HSET', key, 'id', ARGV[1], 'userId', ARGV[2], 'skuId', ARGV[3], 'quantity', ARGV[4],
      'idempotencyKey', ARGV[5], 'requestHash', ARGV[6], 'acceptedAt', ARGV[7], 'deadlineAt', ARGV[8],
      'operation', ARGV[13], 'waitlistEntryId', ARGV[14], 'status', ARGV[9], 'orderId', ARGV[10], 'failureCode', ARGV[11],
      'state', ARGV[9] == 'SUCCEEDED' and 'CONSUMED' or 'RELEASED')
end
redis.call('EXPIRE', KEYS[2], ARGV[12])
return 1
