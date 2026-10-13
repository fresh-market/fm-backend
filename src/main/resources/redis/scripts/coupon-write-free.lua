-- 주인을 잃은 번호를 재고로 되돌린다. docs/coupon/rebuild-raise-each-2026-10-14.md 가 근거다.
-- KEYS[1]=seq(해시)  KEYS[2]=free(정렬집합)  KEYS[3]=counter(문자열)
-- ARGV[1..n]=되살릴 후보 번호. 재건이 DB 로 걸러 넘긴다
-- 반환 실제로 넣은 번호의 수

-- 카운터가 없으면 이벤트가 아니다. 넣을 자리가 없다
local counter = redis.call('GET', KEYS[3])
if counter == false then
  return 0
end

-- 누가 쥔 번호인지는 이 해시의 값으로만 안다.
--
-- pending 으로 못 가린다. 그쪽은 회원으로 키가 잡혀 있어서 "이 번호를 누가 쥐었나" 를 번호로
-- 물을 수 없다.
--
-- 확정 표시가 붙은 것도 쥔 것이다. 그쪽은 DB 행이 있어 후보에 안 들어오지만, 재건이 방금
-- 세운 직후처럼 양쪽이 어긋나는 순간이 있으므로 표시를 떼고 본다.
local held = {}
local flat = redis.call('HGETALL', KEYS[1])
for i = 2, #flat, 2 do
  local value = flat[i]
  local colon = string.find(value, ':')
  if colon then
    value = string.sub(value, 1, colon - 1)
  end
  held[value] = true
end

-- 이 가리기와 넣기가 한 스크립트에 있어야 발급을 멈추지 않아도 안전하다.
--
-- free 에서 꺼낸 번호는 꺼낸 그 Lua 가 seq 에 매핑을 쓴다. 나눠 부르면 그 사이에 꺼내 간
-- 번호를 못 보고 다시 넣어 같은 번호가 두 사람에게 나간다 (같은 문서 5.3).
--
-- NX 로 넣어 멱등이다. 그리고 free 를 통째로 지우지 않는다. 지우고 쓰면 그 틈에 꺼내 간
-- 번호가 되살아난다.
local added = 0
for i = 1, #ARGV do
  local seq = ARGV[i]
  if not held[seq] then
    if redis.call('ZADD', KEYS[2], 'NX', tonumber(seq), seq) == 1 then
      added = added + 1
    end
  end
end

-- 이 스크립트도 free 를 만드는 자리다. 수명을 안 물려주면 넷 중 이 키만 이벤트보다 오래 산다
if added > 0 then
  local deadline = redis.call('PEXPIRETIME', KEYS[3])
  if deadline > 0 then
    redis.call('PEXPIREAT', KEYS[2], deadline)
  end
end

return added
