-- 회원 주문 목록은 member_id로 범위를 좁힌 뒤 주문 시각/ID 내림차순으로 페이지를 만든다.
-- order_id는 ordered_at이 같은 행에서도 페이지 순서를 고정하는 tie-breaker다.
CREATE INDEX idx_orders_member_ordered_at_order_id
    ON orders (member_id, ordered_at DESC, order_id DESC);

-- [메모] status(취소/환불 등) 필터는 아직 이 인덱스에 없다.
-- member_id로 이미 좁혀진 뒤 status는 row 단위로 후처리되는데, 회원 1인당 주문 건수가
-- 적은 지금은 그 비용이 무시할 만하다. 슬로우 쿼리 로그/EXPLAIN으로 실제 필요성이 확인되면
-- 그때 아래처럼 복합 인덱스로 확장하는 쪽을 택한다.
-- CREATE INDEX idx_orders_member_status_ordered_at_order_id
--     ON orders (member_id, status, ordered_at DESC, order_id DESC);


-- [메모] 관리자용 전체 주문 조회는 위의 인덱스를 못 쓴다.
-- member_id로 좁혀지는 게 아니라서 leftmost prefix가 안 맞기 때문이다. 관리자 조회가
-- 생기면 실제로 무엇으로 필터링하는지에 따라 별도로 설계해야 한다: status로 자주
-- 거른다면 아래처럼, 상태 구분 없이 최신순 전체 목록이면 status 없이 ordered_at부터
-- 시작하는 인덱스만으로도 충분하다.
-- CREATE INDEX idx_orders_status_ordered_at_order_id
--     ON orders (status, ordered_at DESC, order_id DESC);
-- CREATE INDEX idx_orders_ordered_at_order_id
--     ON orders (ordered_at DESC, order_id DESC);
--
-- 인덱스뿐 아니라 페이지네이션 방식도 같이 봐야 한다. 관리자 목록은 회원별 조회와 달리
-- 건수 상한이 없어서, 페이지를 깊이 넘길수록 OFFSET 비용이 계속 커진다.
-- "N페이지로 바로 점프"가 꼭 필요한 화면이 아니라면 OFFSET 대신
-- WHERE (ordered_at, order_id) < (:커서_ordered_at, :커서_order_id) 형태의
-- 커서(keyset) 페이지네이션을 우선 고려한다.
