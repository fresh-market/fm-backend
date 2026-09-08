-- 이미 쿠폰을 가진 회원이 들고 온 번호가 안 쓰인 채 버려졌을 때 정리하는 스크립트다.
-- KEYS[1]=seq(해시)  KEYS[2]=pending(정렬집합)  KEYS[3]=free(정렬집합)  KEYS[4]=counter(문자열)
-- ARGV[1]=회원  ARGV[2]=이번에 받았다가 못 쓴 번호  ARGV[3]=그 회원이 원래 갖고 있는 번호
-- 반환 1. 부르는 쪽이 결과를 안 보고 실패만 본다

-- 이번 번호는 아무도 안 썼으므로 다시 내줄 자리에 담는다. 점수가 번호라 낮은 것부터 나간다
redis.call('ZADD', KEYS[3], tonumber(ARGV[2]), ARGV[2])

-- free 를 만드는 자리가 여기뿐이라 여기서 수명을 물려주지 않으면 넷 중 이 키에만 만료가 안 붙는다
-- 앱이 남은 시간을 읽어 다시 거는 대신 절대 시각을 그대로 옮긴다. 왕복도 줄고 어긋남도 없다
local deadline = redis.call('PEXPIRETIME', KEYS[4])
if deadline > 0 then
  redis.call('PEXPIREAT', KEYS[3], deadline)
end

-- 매핑을 지우기만 하면 그 회원의 다음 요청이 또 새 번호를 받아 또 같은 제약에 막힌다
redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
redis.call('ZREM', KEYS[2], ARGV[1])

return 1
