-- 재건이 문을 여는 스크립트다. 카운터를 올리기만 한다.
-- KEYS[1]=counter(문자열)
-- ARGV[1]=재건이 센 최대 순번
-- 반환 문을 연 뒤의 카운터

-- 낮은 값으로 덮으면 안 된다. 그것이 재건이 고치려는 증상을 재건이 만드는 길이다.
--
-- CouponSeqRebuildTrigger.measureBehind 의 불변식이 "counter >= 이 배치의 최대 순번" 이고
-- 거기에 "재건이 살아 있는 티켓보다 낮게 문을 열면 깨진다" 고 적혀 있다.
--
-- 재건은 한 장애에 여러 번 돈다. 재연결 감시가 인스턴스마다 독립으로 띄우고 minInterval 도
-- 각자 세며, 락은 동시 실행만 막으므로 앞 재건이 끝나고 락을 풀면 다음 인스턴스가 또 주도자가
-- 된다. 2026-10-10 N-2 회차에서 두 인스턴스가 1.26초 차로 재연결을 보고 둘 다 주도했다.
--
-- 그 둘이 각자 DB 를 읽는데 읽는 순서와 쓰는 순서가 보장되지 않는다. 먼저 읽은 쪽이 나중에
-- 쓰면 그 낮은 값이 앞서 선 카운터를 뒤로 민다.
--
-- 이 방어가 실측 결함을 고친 것은 아니다. N-2 는 5,784 를 먼저 쓰고 5,859 를 나중에 써서
-- 순서가 맞았다. 다만 그것이 보장이 아니므로 비교를 Redis 안에 둔다. 왕복은 전과 같이 하나다.
--
-- KEEPTTL 을 붙인다. 안 붙이면 이 쓰기가 카운터의 만료를 지운다.
--
-- 카운터가 네 키의 수명을 들고 있어서, 그것이 사라지면 넷이 이벤트보다 오래 산다. 전손 복구는
-- 뒤에서 applyTtl 이 다시 거니까 그 경로만 보면 안 드러나는데, 각 인스턴스가 카운터만 올리는
-- 경로는 그 복원을 안 지나므로 거기서 샌다
-- (docs/coupon/rebuild-v2/design.md 7.5.2).
--
-- 재건은 언제나 올리는 방향이다. 승격으로 뒤로 간 카운터를 DB 가 본 최댓값까지 되돌리는
-- 일이므로 현재 값보다 작은 값을 쓸 이유가 없다.
local want = tonumber(ARGV[1])
local cur = redis.call('GET', KEYS[1])

-- 키가 없으면 세운다. 부재 경로의 재건이 이 자리로 온다
if cur == false then
  redis.call('SET', KEYS[1], want, 'KEEPTTL')
  return want
end

local now = tonumber(cur)

-- 읽을 수 없는 값이면 재건이 센 값으로 바로잡는다
if now == nil then
  redis.call('SET', KEYS[1], want, 'KEEPTTL')
  return want
end

if now < want then
  redis.call('SET', KEYS[1], want, 'KEEPTTL')
  return want
end

return now
