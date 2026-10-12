-- 요청 스레드가 순번 하나를 받아 가는 스크립트다. docs/coupon/coupon.md 3장이 이 설계의 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)  KEYS[4]=pending(정렬집합)
-- ARGV[1]=memberId  ARGV[2]=issueLimit  ARGV[3]=회수 기준(ms)  ARGV[4]=이 인스턴스가 본 최대 순번
-- 만드는 키에는 counter 의 만료 시각을 그대로 물려준다. 넷의 수명이 같아야 한다
-- 반환 "6" 번호를 받았다 / "6:1" 이미 발급됐다 / "-1" 소진(회수 여지 있음)
--      "-2" 준비되지 않았다 / "-3" 소진(최종)

-- 카운터가 없으면 이 스크립트는 순번을 안 내준다. 키가 사라진 뒤에 INCR 이 1 을 다시 주는 것을 막는 가드다
--
-- 이쪽은 -2 를 그대로 낸다. 누군가 재건해야 하는 상태이고 요청이 그 신호다. 재건이 카운터를
-- 세우면 이 분기가 사라지므로 고리가 스스로 닫힌다.
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

-- 카운터가 내가 이미 받은 번호보다 낮으면 복제가 밀린 채 승격된 것이다. 그 번호들은 누군가 쥐고 있다
--
-- 승격은 키를 지우지 않고 값의 꼬리만 자르므로 위의 -2 가 안 나고, 카운터가 사라진 것만 보는
-- 복구는 이 경우를 영영 못 본다. 그래서 한때 재연결을 두 번째 신호로 삼아 주도자가 남의 큐를
-- 모아 고쳤는데, 그 모으기가 분산 합의라 세는 데 실패하는 경우가 고칠 때마다 자리를 옮겼다
-- (fm-backend docs/coupon/rebuild-rearchitecture-2026-10-12.md 1장).
--
-- 여기서 고치면 셀 대상이 없다. 내가 받은 번호는 내가 알고 남이 받은 번호는 남이 아는데,
-- 카운터는 올리기만 하는 값이라 각자 자기 것만 올려도 결과가 전체 최댓값으로 수렴한다.
--
-- n == 1 은 건너뛴다. 갓 준비된 이벤트의 첫 번호라서, 지난 이벤트의 기억으로 뛰면 재고를
-- 통째로 버린다. 카운터가 0 까지 되돌아가는 전손은 위의 -2 가 받는다.
local mark = tonumber(ARGV[4])
if n > 1 and mark and n <= mark then
  n = mark + 1
  -- KEEPTTL 이 없으면 이 쓰기가 카운터의 만료를 지운다. 카운터가 네 키의 수명을 들고 있다
  redis.call('SET', KEYS[3], n, 'KEEPTTL')
end

if n <= tonumber(ARGV[2]) then
  return give(tostring(n))
end

-- 넘긴 만큼만 되돌린다. 위에서 건너뛰었으면 그 값에서 하나를 내린다
--
-- DECR 이 아니라 SET 인 이유는 건너뛴 경우에 하나만 내려서는 안 되기 때문이다.
-- KEEPTTL 이 없으면 DECR 과 달리 만료가 지워진다.
redis.call('SET', KEYS[3], n - 1, 'KEEPTTL')

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
