-- 요청 스레드가 순번 하나를 받아 가는 스크립트다. docs/coupon/coupon.md 3장이 이 설계의 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)  KEYS[4]=pending(정렬집합)
-- KEYS[5]=raising(재건이 도는 동안 서는 문)
--
-- 재건 락을 안 본다. 재건이 발급을 멈추지 않는다.
--
-- 한때 락이 있으면 -2 를 내 그 쿠폰의 발급을 통째로 멈췄다. 재건이 네 키를 다시 세우는 사이에
-- 번호가 나가면 어느 쪽에도 안 잡혀 free 로 나가기 때문이었다. 지금은 그 이유가 셋 다 사라졌다.
--
--   counter   올리기만 하므로 동시에 올려도 결과가 최댓값으로 수렴한다
--   seq       각자 자기 큐만 HSETNX 로 되살려 확정 표시를 덮지 않는다
--   free      쓰는 스크립트가 seq 를 보고 쥔 번호를 건너뛴다. 통째로 지우지 않는다
--
-- 근거는 docs/coupon/rebuild-raise-each-2026-10-14.md 5장이다.
-- ARGV[1]=memberId  ARGV[2]=issueLimit  ARGV[3]=회수 기준(ms)
-- 만드는 키에는 counter 의 만료 시각을 그대로 물려준다. 넷의 수명이 같아야 한다
-- 반환 "6" 번호를 받았다 / "6:1" 이미 발급됐다 / "-1" 소진(회수 여지 있음) / "-2" 준비되지 않았다
--      "-3" 소진(최종) / "-4" 재건이 도는 중

-- 카운터가 없으면 이 스크립트는 순번을 안 내준다. 키가 사라진 뒤에 INCR 이 1 을 다시 주는 것을 막는 가드다
-- 재건이 도는 동안에는 번호를 안 내준다.
--
-- 멈추는 까닭이 느려서가 아니라 틀린 답을 주기 때문이다. 승격 직후에는 카운터가 살아 있는데
-- 값이 뒤로 가 있을 수 있고, 그 값으로 INCR 하면 이미 번호를 쥔 회원에게 새 번호가 나간다.
-- 그 회원의 INSERT 둘이 uk_mc_coupon_member 에서 부딪히고, 재시도가 먼저 커밋하면 먼저 온
-- 사람이 맨 뒤로 간다. 순번은 도착 순서의 기록이라 그것을 잃을 이유가 사용자 쪽에 없다
-- (README 5.6, docs/coupon/rebuild-gate-2026-10-31.md 1장).
--
-- 이 문은 시간으로 열린다. 세운 대가 죽어도 저절로 열리고 아무도 안 풀어도 된다.
if redis.call('EXISTS', KEYS[5]) == 1 then
  return '-4'
end

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

-- 소진이 늘 최종은 아니다. 미확정 순번을 쥔 사람이 있으면 기준 시간이 지날 때 그 번호가 다시 나온다
-- 아무도 안 쥐고 있으면 다시 나올 번호가 없다. 회수도 반납도 진행 중인 티켓을 전제하기 때문이다
if redis.call('ZCARD', KEYS[4]) == 0 then
  return '-3'
end

return '-1'
