-- 선착순 발급의 순번 확보. docs/coupon/coupon.md 3장이 이 스크립트의 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)  KEYS[4]=pending(정렬집합)
-- ARGV[1]=memberId  ARGV[2]=issueLimit  ARGV[3]=지금(ms)  ARGV[4]=회수 기준(ms)
-- 반환 "6" 번호를 받았다 / "6:1" 이미 발급됐다 / "-1" 소진 / "-2" 준비되지 않았다

-- 카운터가 없으면 순번을 내주지 않는다. 재건 전에 INCR 이 1 을 주는 것을 막는 가드다
if redis.call('EXISTS', KEYS[3]) == 0 then
  return '-2'
end

-- 이미 번호를 받은 회원이면 그대로 돌려준다. 재시도가 새 번호를 태우지 않는다
local mine = redis.call('HGET', KEYS[1], ARGV[1])
if mine then
  return mine
end

-- 번호를 주면서 미확정으로 표시한다. 커밋 뒤에 플러시 스레드가 확정 표시를 붙이고 pending 에서 뺀다
local function give(seq)
  redis.call('HSET', KEYS[1], ARGV[1], seq)
  redis.call('ZADD', KEYS[4], ARGV[3], ARGV[1])
  return seq
end

-- 반납분부터 쓴다. 낮은 번호가 먼저 나가 MAX(issue_seq) 가 덜 벌어진다
local returned = redis.call('ZPOPMIN', KEYS[2])
if returned[1] then
  return give(returned[1])
end

local n = redis.call('INCR', KEYS[3])
if n <= tonumber(ARGV[2]) then
  return give(tostring(n))
end
redis.call('DECR', KEYS[3])

-- 여기부터 소진이다. 묶인 번호가 있으면 회수해 이 요청에게 준다
-- 재고가 남아 있는 동안에는 이 구간에 오지 않으므로 평상시 비용이 0 이다
local cut = tonumber(ARGV[3]) - tonumber(ARGV[4])
local stale = redis.call('ZRANGEBYSCORE', KEYS[4], 0, cut, 'LIMIT', 0, 1)
if stale[1] then
  local seq = redis.call('HGET', KEYS[1], stale[1])
  redis.call('ZREM', KEYS[4], stale[1])
  -- 확정 표시가 붙은 것은 실제로 발급된 것이라 회수하지 않는다. pending 에서만 뺀다
  if seq and not string.find(seq, ':') then
    redis.call('HDEL', KEYS[1], stale[1])
    return give(seq)
  end
end

return '-1'
