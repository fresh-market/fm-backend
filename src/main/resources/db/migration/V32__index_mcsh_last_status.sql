-- =====================================================================
-- 정합성 검증 배치의 상태·이력 대조용 커버링 인덱스(coupon 도메인)
-- =====================================================================
-- 기존 idx_mcsh_coupon_time(member_coupon_id, member_coupon_status_history_id) 을
-- 같은 선두 두 컬럼에 to_status 를 더한 인덱스로 교체한다. 선두가 같으므로 기존
-- 인덱스가 하던 일은 새 인덱스가 모두 대신한다. 인덱스 개수는 늘지 않는다.
--
-- to_status 가 인덱스에 들어가면 "쿠폰의 마지막 전이 상태" 를 행 본문까지 가지 않고
-- 인덱스만 읽어 구할 수 있다. 배치의 상태·이력 대조가 그 조회를 발급분 수만큼 돌린다.
--
-- 실제로 타는 것을 확인했다. 발급 300만 / 이력 420만에서 STATUS_HISTORY_MISMATCH_SQL 의
-- EXPLAIN ANALYZE 가 아래를 냈다. rows=1.4 loops=3e+6 이 발급분마다 한 번씩 도는 조회이고,
-- Covering 이 붙어 행 본문을 읽지 않는다는 뜻이다.
--
--   -> Covering index lookup on h using idx_mcsh_last_status
--        (member_coupon_id=mc.member_coupon_id)
--        (cost=1.14 rows=1.37) (actual time=0.00222..0.00251 rows=1.4 loops=3e+6)
--
-- 이 교체가 이득인 것은 버퍼풀이 작업 세트보다 한참 작을 때다. 운영 DB(db.t4g.micro)의
-- innodb_buffer_pool_size 가 128MB 인데 member_coupon 과 이력 표를 합치면 1.2GB 를 넘어,
-- 어느 쪽 경로든 디스크를 타는 상태다. 그 조건에서는 읽는 총량이 적은 커버링 인덱스가
-- 유리하다. 같은 데이터에서 상태·이력 대조가 16.7초에서 14.4초가 됐다(3회 교차 측정 평균).
--
-- 반대로 버퍼풀이 작업 세트를 담을 만큼 커지면 이 이점이 사라지고, 집합 연산으로 한 번에
-- 훑는 쪽이 다시 빨라진다(512MB 부터 역전됐다). 인스턴스를 키우거나 버퍼풀을 올릴 때는
-- CouponConsistencyRepository 의 조회 방식과 이 인덱스를 함께 다시 재야 한다.
--
-- 두 문장 모두 알고리즘을 명시한다. 이력 표가 1.2GB 를 넘어 자란 표라, 명시하지 않으면
-- MySQL 이 조용히 COPY 로 표를 통째로 다시 만들 수 있다(V30 과 같은 이유). 명시하면
-- 온라인으로 못 할 때 실행 대신 오류를 내므로 운영이 아니라 배포 시점에 막힌다.
--
-- ADD 를 DROP 보다 먼저 둔다. fk_mcsh_member_coupon 이 member_coupon_id 에 인덱스를
-- 요구하는데, 먼저 지우면 대체할 인덱스가 없어 "Cannot drop index ... needed in a
-- foreign key constraint" 로 막힌다. 새 인덱스가 있으면 FK 가 그쪽으로 넘어간다.

ALTER TABLE member_coupon_status_history
    ADD INDEX idx_mcsh_last_status (member_coupon_id, member_coupon_status_history_id, to_status),
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE member_coupon_status_history
    DROP INDEX idx_mcsh_coupon_time,
    ALGORITHM=INPLACE, LOCK=NONE;
