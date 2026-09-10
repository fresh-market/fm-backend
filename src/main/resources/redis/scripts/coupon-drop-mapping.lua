-- 남이 쓰고 있는 번호를 받은 회원들의 매핑을 지우는 스크립트다.
-- KEYS[1]=seq(해시)  KEYS[2]=pending(정렬집합)
-- ARGV = 회원 목록
-- 반환 1. 부르는 쪽이 결과를 안 보고 실패만 본다

-- 번호는 반납하지 않는다. 남이 쓰는 번호를 free 에 넣으면 또 다른 회원에게 내주게 된다
redis.call('HDEL', KEYS[1], unpack(ARGV))
redis.call('ZREM', KEYS[2], unpack(ARGV))

return 1
