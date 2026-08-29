-- 요청 횟수 증가와 최초 요청의 TTL 설정을 한 번에 처리한다.
-- INCR와 EXPIRE를 별도 명령으로 보내면 그 사이의 앱/네트워크 장애로
-- 만료되지 않는 카운터 키가 남을 수 있다.
local count = redis.call('INCR', KEYS[1])

if count == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[1])
end

return count
