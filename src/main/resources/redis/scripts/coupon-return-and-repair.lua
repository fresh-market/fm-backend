-- 이미 쿠폰을 가진 회원들이 들고 온 번호가 안 쓰인 채 버려졌을 때 정리하는 스크립트다.
-- KEYS[1]=seq(해시)  KEYS[2]=pending(정렬집합)  KEYS[3]=free(정렬집합)  KEYS[4]=counter(문자열)
-- ARGV = 회원, 못 쓴 번호, 원래 갖고 있는 값 의 세 개씩 반복이다
-- 반환 1. 부르는 쪽이 결과를 안 보고 실패만 본다

local members = {}
local freed = {}
local fields = {}
for i = 1, #ARGV, 3 do
  members[#members + 1] = ARGV[i]
  -- ZADD 는 점수를 앞에 받는다. 점수가 번호라 낮은 것부터 다시 나간다
  freed[#freed + 1] = tonumber(ARGV[i + 1])
  freed[#freed + 1] = ARGV[i + 1]
  -- HSET 은 필드와 값을 번갈아 받는다
  fields[#fields + 1] = ARGV[i]
  fields[#fields + 1] = ARGV[i + 2]
end

redis.call('ZADD', KEYS[3], unpack(freed))

-- free 를 만드는 자리가 여기뿐이라 여기서 수명을 물려주지 않으면 넷 중 이 키에만 만료가 안 붙는다
-- 앱이 남은 시간을 읽어 다시 거는 대신 절대 시각을 그대로 옮긴다. 왕복도 줄고 어긋남도 없다
local deadline = redis.call('PEXPIRETIME', KEYS[4])
if deadline > 0 then
  redis.call('PEXPIREAT', KEYS[3], deadline)
end

-- 매핑을 지우기만 하면 그 회원의 다음 요청이 또 새 번호를 받아 또 같은 제약에 막힌다
redis.call('HSET', KEYS[1], unpack(fields))
redis.call('ZREM', KEYS[2], unpack(members))

return 1
