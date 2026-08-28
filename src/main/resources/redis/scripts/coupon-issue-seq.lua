-- 선착순 발급의 순번 확보. docs/coupon/coupon.md 3장이 이 스크립트의 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)  KEYS[4]=pending(정렬집합)
-- ARGV[1]=memberId  ARGV[2]=issueLimit  ARGV[3]=회수 기준(ms)
-- 만드는 키에는 counter 의 만료 시각을 그대로 물려준다. 넷의 수명이 같아야 한다
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

-- 시각은 앱이 아니라 Redis 에서 받는다. 점수를 쓰는 인스턴스와 재는 인스턴스가 달라서다
-- 앱 시계로 하면 어긋난 한 대가 살아 있는 요청의 번호까지 오래된 것으로 보고 뺏는다
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

-- 네 키는 함께 살고 함께 죽는다. 수명은 counter 가 갖고 있고 나머지는 그것을 따라간다
-- 앱이 준비 단계에서 넷에 걸 수는 없다. 그때는 counter 만 있고 없는 키에 EXPIREAT 은 아무 일도 안 한다
-- 그래서 만드는 자리인 여기가 건다. 값을 앱이 넘기지 않으므로 마감을 바꿔도 저절로 따라간다
local deadline = redis.call('PEXPIRETIME', KEYS[3])

local function inherit(key)
  if deadline > 0 then
    redis.call('PEXPIREAT', key, deadline)
  end
end

-- 번호를 주면서 미확정으로 표시한다. 커밋 뒤에 플러시 스레드가 확정 표시를 붙이고 pending 에서 뺀다
local function give(seq)
  redis.call('HSET', KEYS[1], ARGV[1], seq)
  redis.call('ZADD', KEYS[4], now, ARGV[1])
  inherit(KEYS[1])
  inherit(KEYS[4])
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
local cut = now - tonumber(ARGV[3])
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
