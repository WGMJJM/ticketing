-- KEYS: readyMarker, usernameBitmap, emailBitmap
-- ARGV: operation, bitmapIndex/hashCount/bitSize, geometry, offsets...
if ARGV[1] == 'contains' then
  if redis.call('GET', KEYS[1]) ~= ARGV[3] then return -1 end
  if redis.call('EXISTS', KEYS[2], KEYS[3]) ~= 2 then
    redis.call('DEL', KEYS[1])
    return -1
  end
  for i = 4, #ARGV do
    if redis.call('GETBIT', KEYS[tonumber(ARGV[2])], ARGV[i]) == 0 then return 0 end
  end
  return 1
end
if ARGV[1] == 'add' then
  -- 位图被清除后，追加新账号不能把不完整的历史位图视为已就绪。
  if redis.call('EXISTS', KEYS[2], KEYS[3]) ~= 2 then redis.call('DEL', KEYS[1]) end
  local hashes = tonumber(ARGV[2])
  for base = 4, #ARGV, hashes * 2 do
    for bitmap = 2, 3 do
      for i = 0, hashes - 1 do
        local offset = ARGV[base + (bitmap - 2) * hashes + i]
        if offset ~= '-1' then redis.call('SETBIT', KEYS[bitmap], offset, 1) end
      end
    end
  end
  return 1
end
if ARGV[1] == 'ready' then
  for bitmap = 2, 3 do
    if redis.call('EXISTS', KEYS[bitmap]) == 0 then redis.call('SETBIT', KEYS[bitmap], tonumber(ARGV[2]) - 1, 0) end
  end
  redis.call('SET', KEYS[1], ARGV[3])
  return 1
end
return -1
