-- 관리자 상품 목록(ProductQueryRepository.searchForAdmin)은 카테고리로 좁힌 뒤 최신순으로 페이지를
-- 만든다(idx_orders_member_ordered_at_order_id와 같은 방식). category_id는 FK라 단일 컬럼 인덱스는
-- 있지만 created_at/product_id까지 묶이지 않아, 카테고리 필터 + 최신순 정렬에 filesort가 붙는다.
-- saleStatus만 필터하거나 필터가 아예 없는 조회는 이 인덱스로 타지 않는다(카테고리 필터 위주 사용
-- 패턴으로 우선순위를 정함).
CREATE INDEX idx_product_category_created ON product (category_id, created_at DESC, product_id DESC);
