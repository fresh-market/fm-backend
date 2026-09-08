-- 플러시 스레드가 커밋을 마친 회원들에게 확정 표시를 붙이고 미확정 목록에서 빼는 스크립트다.
-- KEYS[1]=seq(해시)  KEYS[2]=pending(정렬집합)
-- ARGV = 회원, 값, 회원, 값, ... 의 쌍이다. 값은 "6:1" 처럼 확정 표시가 붙은 순번이다
-- 반환 1. 부르는 쪽이 결과를 안 보고 실패만 본다

-- HSET 이 받는 인자 모양이 ARGV 와 같아서 그대로 넘긴다
redis.call('HSET', KEYS[1], unpack(ARGV))

-- ZREM 은 회원만 받는다. 홀수 자리만 골라낸다
local members = {}
for i = 1, #ARGV, 2 do
  members[#members + 1] = ARGV[i]
end
redis.call('ZREM', KEYS[2], unpack(members))

return 1
