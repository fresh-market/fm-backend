-- KEYS[1] = 옛 리프레시 토큰 키(refreshToken:{옛 토큰 해시})
-- KEYS[2] = 새 리프레시 토큰 키(refreshToken:{새 토큰 해시})
-- ARGV[1] = 새 토큰 TTL(ms)
--
-- opaque 전환(2026-08-19) 이후: 리프레시 토큰 자체엔 아무 정보가 없어서(SEC-1-04, JWT처럼
-- role/id를 클라이언트가 보낸 토큰에서 직접 못 꺼낸다) 원자적 CAS의 형태가 바뀌었다 — 예전엔
-- "고정된 role:id 슬롯의 값이 옛 해시와 같으면 새 해시로 교체"였는데, 이제는 키 자체가 토큰마다
-- 다르니 "옛 토큰 키가 있으면 그 값(memberId|role|type|remember)을 새 토큰 키로 그대로 옮기고
-- 옛 키는 지운다"가 된다.
--
-- (2026-08-19 추가) 옛 키를 곧바로 DEL 하면, 그 죽은 토큰이 나중에 재생(replay)됐을 때 "누구
-- 것이었는지" 알 방법이 없어져서 재사용 탐지는 되지만(값이 없으니 실패 처리) 그 회원의 다른
-- 유효 세션을 강제로 끊는 부가 조치를 할 수가 없었다. 그래서 DEL 대신 값 뒤에 "|REVOKED"
-- 마커를 붙여 tombstone으로 남겨둔다 — 재사용 시도가 들어오면 이 마커를 보고 소유자 정보를
-- 그대로 돌려줘서, 호출부(MemberTokenService)가 그 회원의 현재 세션을 강제 종료할 수 있게 한다.
-- tombstone 상태에선 회전이 다시 성공해서는 안 된다(재사용 시도가 새 유효 토큰을 만들어내면
-- 안 되니까) — 그래서 REVOKED 마커가 있으면 그대로 반환만 하고 상태를 바꾸지 않는다.
--
-- tombstone의 유효기간은 별도로 정하지 않고, 옛 키에 원래 남아있던 TTL(PTTL)을 그대로 재사용
-- 한다. 리프레시 토큰은 1회용이라 "이 토큰이 원래 유효했을 남은 시간" 동안은 언제든 재생될 수
-- 있으니, tombstone도 그 시간만큼은 살아있어야 재사용 탐지가 새지 않는다 — 고정된 짧은 유예
-- 시간(예: 1분)으로는 그보다 늦게 재생되는 시도를 못 잡는다.
--
-- Redis가 싱글스레드라 이 스크립트 전체가 원자적으로 실행되므로, 동시에 같은 옛 토큰으로 두 번
-- 재발급 요청이 와도 하나만 정상 회전에 성공한다.
--
-- 반환값 셋:
--   false                                      : 이 키가 원래 없었거나(또는 tombstone까지 자연
--                                                 만료됨) 완전 미상
--   "memberId|role|type|remember"               : 정상 회전 성공(최초 사용)
--   "memberId|role|type|remember|REVOKED"        : 재사용 탐지 — 소유자 정보 포함, 회전은 실패 처리

local value = redis.call('GET', KEYS[1])
if not value then
    return false
end

if string.sub(value, -8) == '|REVOKED' then
    return value
end

local remainingTtl = redis.call('PTTL', KEYS[1])

redis.call('SET', KEYS[2], value, 'PX', ARGV[1])
if remainingTtl > 0 then
    redis.call('SET', KEYS[1], value .. '|REVOKED', 'PX', remainingTtl)
else
    -- PTTL이 0/음수로 나오는 건 이론상 거의 없다(방금 GET으로 값을 읽었으니 그 순간엔 살아있었다는
    -- 뜻이라 TTL도 양수였어야 한다) — 그래도 방어적으로, TTL 정보를 못 구하면 tombstone 없이
    -- 그냥 지운다(예전 동작으로 안전하게 후퇴).
    redis.call('DEL', KEYS[1])
end
return value
