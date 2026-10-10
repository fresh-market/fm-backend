-- 재건이 문을 여는 스크립트다. 카운터를 올리기만 한다.
-- KEYS[1]=counter(문자열)
-- ARGV[1]=재건이 센 최대 순번
-- 반환 문을 연 뒤의 카운터

-- 낮은 값으로 덮으면 안 된다. 그것이 재건이 고치려는 증상을 재건이 만드는 길이다.
--
-- CouponSeqRebuildTrigger.measureBehind 의 불변식이 "counter >= 이 배치의 최대 순번" 이고
-- 거기에 "재건이 살아 있는 티켓보다 낮게 문을 열면 깨진다" 고 적혀 있다.
--
-- 재건은 한 장애에 여러 번 돌 수 있다. 재연결 감시가 인스턴스마다 독립으로 띄우고 락은 동시
-- 실행만 막으므로, 앞 재건이 락을 풀면 다음 인스턴스가 또 주도자가 된다. 부하가 도는 중에는
-- DB 가 계속 자라서 나중 재건이 더 이른 스냅샷을 읽을 수 있고, 그 낮은 값이 앞서 선 카운터를
-- 뒤로 민다. 2026-10-10 N-2 회차에서 5,859 다음에 5,784 가 써졌다.
--
-- 재건은 언제나 올리는 방향이다. 승격으로 뒤로 간 카운터를 DB 가 본 최댓값까지 되돌리는
-- 일이므로 현재 값보다 작은 값을 쓸 이유가 없다.
local want = tonumber(ARGV[1])
local cur = redis.call('GET', KEYS[1])

-- 키가 없으면 세운다. 부재 경로의 재건이 이 자리로 온다
if cur == false then
  redis.call('SET', KEYS[1], want)
  return want
end

local now = tonumber(cur)

-- 읽을 수 없는 값이면 재건이 센 값으로 바로잡는다
if now == nil then
  redis.call('SET', KEYS[1], want)
  return want
end

if now < want then
  redis.call('SET', KEYS[1], want)
  return want
end

return now
