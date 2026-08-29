-- 요청 스레드가 순번 하나를 받아 가는 스크립트다. docs/coupon/coupon.md 3장이 이 설계의 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)  KEYS[4]=pending(정렬집합)
-- ARGV[1]=memberId  ARGV[2]=issueLimit  ARGV[3]=회수 기준(ms)
-- 만드는 키에는 counter 의 만료 시각을 그대로 물려준다. 넷의 수명이 같아야 한다
-- 반환 "6" 번호를 받았다 / "6:1" 이미 발급됐다 / "-1" 소진 / "-2" 준비되지 않았다

-- 카운터가 없으면 이 스크립트는 순번을 안 내준다. 키가 사라진 뒤에 INCR 이 1 을 다시 주는 것을 막는 가드다
if redis.call('EXISTS', KEYS[3]) == 0 then
  return '-2'
end

-- 이 회원이 이미 번호를 받았으면 그 번호를 그대로 돌려준다. 그래야 재시도가 새 번호를 안 태운다
local mine = redis.call('HGET', KEYS[1], ARGV[1])
if mine then
  return mine
end

-- 이 스크립트는 시각을 앱이 아니라 Redis 에서 받는다. 점수를 쓰는 인스턴스와 재는 인스턴스가 달라서다
-- 앱 시계를 쓰면 시계가 어긋난 인스턴스 한 대가 아직 살아 있는 요청의 번호까지 오래된 것으로 보고 뺏는다
local t = redis.call('TIME')
local now = t[1] * 1000 + math.floor(t[2] / 1000)

-- 네 키는 함께 살고 함께 죽어야 한다. 수명은 counter 가 들고 있고 나머지 셋이 그것을 따라간다
-- 앱이 이벤트를 준비하는 단계에서 넷에 한꺼번에 걸 수는 없다. 그때는 counter 만 있고, 없는 키에 EXPIREAT 은 아무 일도 안 한다
-- 그래서 키를 만드는 자리인 이 스크립트가 건다. 값을 앱이 넘기지 않으므로 관리자가 마감을 바꿔도 저절로 따라간다
local deadline = redis.call('PEXPIRETIME', KEYS[3])

local function inherit(key)
  if deadline > 0 then
    redis.call('PEXPIREAT', key, deadline)
  end
end

-- 이 함수가 번호를 주면서 그 회원을 미확정으로 표시한다. DB 커밋 뒤에 플러시 스레드가 확정 표시를 붙이고 pending 에서 뺀다
local function give(seq)
  redis.call('HSET', KEYS[1], ARGV[1], seq)
  redis.call('ZADD', KEYS[4], now, ARGV[1])
  inherit(KEYS[1])
  inherit(KEYS[4])
  return seq
end

-- 반납된 번호부터 쓴다. 낮은 번호가 먼저 나가야 MAX(issue_seq) 와 실제 발급 수의 간격이 덜 벌어진다
local returned = redis.call('ZPOPMIN', KEYS[2])
if returned[1] then
  return give(returned[1])
end

local n = redis.call('INCR', KEYS[3])
if n <= tonumber(ARGV[2]) then
  return give(tostring(n))
end
redis.call('DECR', KEYS[3])

-- 여기까지 왔으면 소진이다. 번호만 받고 사라진 요청이 있으면 그 번호를 회수해 이 요청에게 준다
-- 재고가 남아 있는 동안에는 이 스크립트가 여기까지 안 오므로 평상시에 드는 비용이 없다
local cut = now - tonumber(ARGV[3])
local stale = redis.call('ZRANGEBYSCORE', KEYS[4], 0, cut, 'LIMIT', 0, 1)
if stale[1] then
  local seq = redis.call('HGET', KEYS[1], stale[1])
  redis.call('ZREM', KEYS[4], stale[1])
  -- 확정 표시가 붙은 번호는 실제로 발급이 끝난 것이라 회수하면 안 된다. pending 에서만 뺀다
  if seq and not string.find(seq, ':') then
    redis.call('HDEL', KEYS[1], stale[1])
    return give(seq)
  end
end

return '-1'
