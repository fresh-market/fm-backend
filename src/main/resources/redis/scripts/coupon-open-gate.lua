-- 재건이 카운터를 세우는 스크립트다. 카운터를 올리기만 하고 네 키의 수명을 함께 건다.
-- KEYS[1]=counter(문자열)  KEYS[2]=seq(해시)  KEYS[3]=free(정렬집합)  KEYS[4]=pending(정렬집합)
-- ARGV[1]=재건이 센 최대 순번
-- ARGV[2]=네 키가 사라질 시각(밀리초). 0 이면 이벤트에 마감이 없어 걸 기준이 없다
-- 반환 쓴 뒤의 카운터

-- 낮은 값으로 덮으면 안 된다. 그것이 재건이 고치려는 증상을 재건이 만드는 길이다.
--
-- CouponSeqRebuildTrigger.measureBehind 의 불변식이 "counter >= 이 배치의 최대 순번" 이고
-- 거기에 "재건이 살아 있는 티켓보다 낮게 카운터를 세우면 깨진다" 고 적혀 있다.
--
-- 재건은 한 장애에 여러 번 돈다. 재연결 감시가 인스턴스마다 독립으로 띄우고 minInterval 도
-- 각자 세며, 락은 동시 실행만 막으므로 앞 재건이 끝나고 락을 풀면 다음 인스턴스가 또 주도자가
-- 된다. 2026-10-10 N-2 회차에서 두 인스턴스가 1.26초 차로 재연결을 보고 둘 다 주도했다.
--
-- 그 둘이 각자 DB 를 읽는데 읽는 순서와 쓰는 순서가 보장되지 않는다. 먼저 읽은 쪽이 나중에
-- 쓰면 그 낮은 값이 앞서 선 카운터를 뒤로 민다.
--
-- 이 방어가 실측 결함을 고친 것은 아니다. N-2 는 5,784 를 먼저 쓰고 5,859 를 나중에 써서
-- 순서가 맞았다. 다만 그것이 보장이 아니므로 비교를 Redis 안에 둔다.
--
-- KEEPTTL 을 붙인다. 안 붙이면 이 쓰기가 카운터의 만료를 지운다. ARGV[2] 가 있으면 아래에서
-- 어차피 다시 걸지만, 마감이 없는 이벤트는 그 경로를 안 지나므로 여기서 지키는 것이 맞다.
--
-- 재건은 언제나 올리는 방향이다. 승격으로 뒤로 간 카운터를 DB 가 본 최댓값까지 되돌리는
-- 일이므로 현재 값보다 작은 값을 쓸 이유가 없다.
local want = tonumber(ARGV[1])
local cur = redis.call('GET', KEYS[1])
local opened

if cur == false then
  -- 키가 없으면 세운다. 부재 경로의 재건이 이 자리로 온다
  redis.call('SET', KEYS[1], want, 'KEEPTTL')
  opened = want
else
  local now = tonumber(cur)
  if now == nil then
    -- 읽을 수 없는 값이면 재건이 센 값으로 바로잡는다
    redis.call('SET', KEYS[1], want, 'KEEPTTL')
    opened = want
  elseif now < want then
    redis.call('SET', KEYS[1], want, 'KEEPTTL')
    opened = want
  else
    opened = now
  end
end

-- 수명을 여기서 같이 건다. 예전에는 자바가 EXPIREAT 으로 카운터에 걸고, 그 값을 PTTL 로
-- 되읽어 나머지 셋에 걸었다. 왕복이 다섯이었고 그 사이에 재건이 죽으면 셋이 수명 없이 남았다.
--
-- 카운터가 넷의 수명을 들고 있어서, 그것이 사라지면 넷이 이벤트보다 오래 산다. 평상시에는
-- 순번 확보가 키를 만들면서 물려받는데, 재건은 카운터보다 먼저 만들어 그 경로를 안 지난다.
--
-- ARGV[2] 가 0 이면 마감이 없는 이벤트다. 그때는 카운터에 이미 걸려 있는 값을 나머지 셋에
-- 물려준다. 없는 키에 PEXPIREAT 은 아무 일도 안 한다.
local deadline = tonumber(ARGV[2])
if deadline > 0 then
  redis.call('PEXPIREAT', KEYS[1], deadline)
else
  deadline = redis.call('PEXPIRETIME', KEYS[1])
end

if deadline > 0 then
  redis.call('PEXPIREAT', KEYS[2], deadline)
  redis.call('PEXPIREAT', KEYS[3], deadline)
  redis.call('PEXPIREAT', KEYS[4], deadline)
end

return opened
