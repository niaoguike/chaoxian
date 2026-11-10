-- One Redis primary is required; keys intentionally share this atomic execution.
local stock = 'seckill:stock:' .. ARGV[1]
local buyers = 'seckill:order:' .. ARGV[1]
local tx = 'seckill:tx:' .. ARGV[3]
local previous = redis.call('get', tx)
if previous == 'COMMIT' then return 0 end
if previous then return 3 end
local now = tonumber(redis.call('time')[1])
local beginAt = tonumber(redis.call('get', 'seckill:begin:' .. ARGV[1]) or '0')
local endAt = tonumber(redis.call('get', 'seckill:end:' .. ARGV[1]) or '0')
if now < beginAt or now >= endAt then redis.call('set', tx, 'ABORT'); return 3 end
if tonumber(redis.call('get', stock) or '0') <= 0 then redis.call('set', tx, 'ABORT'); return 1 end
if redis.call('sismember', buyers, ARGV[2]) == 1 then redis.call('set', tx, 'ABORT'); return 2 end
redis.call('incrby', stock, -1)
redis.call('sadd', buyers, ARGV[2])
redis.call('set', tx, 'COMMIT')
return 0
