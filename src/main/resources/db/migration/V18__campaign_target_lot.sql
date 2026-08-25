-- =====================================================================
-- 선착순 쿠폰 캠페인 대상 로트(stock 도메인)
-- =====================================================================
-- 매일 자정 배치가 그날의 캠페인 대상을 확정해 여기 남긴다.
-- 조회는 이 표만 읽는다. 소진율과 재고는 초 단위로 변하므로, 요청 시점마다
-- 다시 계산하면 같은 기준일인데도 결과가 달라진다. 확정본을 두는 이유다.
-- (요구사항: "동일 기준일로 재조회 시 항상 동일 결과 반환")
--
-- 상품이 아니라 로트 단위로 둔다. 같은 상품이라도 로트마다 소비기한이 달라,
-- 상품 단위로 두면 소비기한이 넉넉히 남은 로트까지 쿠폰이 적용된다.

CREATE TABLE campaign_target_lot (
    campaign_target_lot_id BIGINT       NOT NULL AUTO_INCREMENT, -- campaign_target_lot PK
    target_date             DATE         NOT NULL, -- 대상 확정 기준일(배치 실행일)
    stock_lot_id             BIGINT       NOT NULL, -- 대상 로트 FK
    turnover_rate            DECIMAL(5,4) NOT NULL, -- 확정 시점 소진율 (입고수량-잔여재고)/입고수량. 0.0000~1.0000
    issuable_qty             INT          NOT NULL, -- 발급 가능 수량(확정 시점 로트 잔량 기준)
    target_rank              INT          NOT NULL, -- 소진율 오름차순 순위(1이 가장 낮음). 상위 3건만 남긴다
    created_at               DATETIME(6)  NOT NULL, -- 생성 시각(애플리케이션에서 생성)
    PRIMARY KEY (campaign_target_lot_id),
    CONSTRAINT uk_campaign_target_date_lot UNIQUE (target_date, stock_lot_id), -- 같은 날 같은 로트를 두 번 넣지 않는다(배치 재실행 시 덮어쓰기 판단 기준)
    CONSTRAINT fk_campaign_target_lot FOREIGN KEY (stock_lot_id) REFERENCES stock_lot (stock_lot_id),
    CONSTRAINT chk_campaign_turnover_rate CHECK (turnover_rate >= 0 AND turnover_rate <= 1),
    CONSTRAINT chk_campaign_issuable_qty CHECK (issuable_qty >= 0),
    CONSTRAINT chk_campaign_target_rank CHECK (target_rank >= 1 AND target_rank <= 3), -- 상위 3건만 남긴다는 요구사항 그 자체를 DB에서도 강제한다 (DI-3-02)
    KEY idx_campaign_target_date (target_date, target_rank) -- 당일 대상을 순위대로 조회
); -- 선착순 쿠폰 캠페인 대상 로트(자정 배치가 확정한 그날의 스냅샷)
