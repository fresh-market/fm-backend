-- =====================================================================
-- 소비기한 범위 조회용 인덱스(stock 도메인)
-- =====================================================================
-- 캠페인 대상 로트 배치가 소비기한 구간(판매 마감 기한 ~ 임박 시작선)으로 후보를 찾는다.
-- 기존 idx_lot_fefo(product_option_id, status, expiry_date) 는 선두가 product_option_id 라
-- 옵션을 조건에 안 쓰는 이 조회에서는 탈 수 없어 stock_lot 전체를 훑게 된다.
--
-- stock_lot 은 EXPIRED/DISPOSED 로트가 지워지지 않고 쌓이는 표라, 정작 필요한 행 수는
-- 거의 일정한데 훑는 양만 영업 기간에 비례해 늘어난다. 그 증가를 끊는 것이 목적이다.
--
-- 선두를 status 가 아니라 expiry_date 로 잡은 이유가 있다. 상태 비교는
-- CollationExpressions 가 collate(status as ...) 로 컬럼을 감싸는데, 컬럼에 함수가 씌워지면
-- MySQL 이 그 컬럼의 인덱스를 쓰지 못한다. 함수에 걸리지 않는 expiry_date 를 선두로 둔다.
--
-- available_qty 는 넣지 않는다. 주문마다 바뀌는 값이라 인덱스에 넣으면 예약 경로(가장 잦은
-- 쓰기)마다 인덱스를 갱신하게 된다. 하루 한 번 도는 배치를 위해 치를 값이 아니다.
-- expiry_date 는 입고 후 바뀌지 않아 유지 비용이 입고 시점뿐이다.

CREATE INDEX idx_lot_expiry_date ON stock_lot (expiry_date);
