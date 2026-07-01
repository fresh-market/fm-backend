-- =====================================================================
-- 신선식품 자사몰 DDL (MySQL 8.0 / InnoDB / utf8mb4)
-- ERD 기준 추출본. 테이블은 FK 의존성 순서로 정의됨.
-- 표준 enum 값은 ENUM 타입으로, 가변 가능성이 있는 값은 그대로 ENUM 사용.
-- =====================================================================

SET NAMES utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
-- 1. 회원 / 권한
-- =====================================================================

CREATE TABLE member_grade (
    grade_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL COMMENT '등급명 (기본/단골 등)',
    discount_rate DECIMAL(5, 2) NOT NULL DEFAULT 0.00 COMMENT '등급 할인율(%)',
    promotion_rule VARCHAR(255) NULL COMMENT '승급 기준',
    PRIMARY KEY (grade_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '회원 등급';

CREATE TABLE member (
    member_id BIGINT NOT NULL AUTO_INCREMENT,
    email VARCHAR(255) NOT NULL COMMENT '로그인 아이디(이메일, 원문-암호화 추후)',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt 단방향 해시',
    name VARCHAR(50) NOT NULL COMMENT '이름(원문, 암호화 추후 적용)',
    nickname VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NOT NULL COMMENT '휴대전화(원문, 암호화 추후 적용)',
    grade_id BIGINT NOT NULL,
    marketing_agreed TINYINT(1) NOT NULL DEFAULT 0,
    status ENUM(
        'ACTIVE',
        'BLOCKED',
        'WITHDRAWN'
    ) NOT NULL DEFAULT 'ACTIVE',
    deleted_at DATETIME NULL COMMENT '소프트딜리트',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (member_id),
    UNIQUE KEY uk_member_email (email),
    UNIQUE KEY uk_member_nickname (nickname),
    KEY idx_member_phone (phone),
    KEY idx_member_grade (grade_id),
    CONSTRAINT fk_member_grade FOREIGN KEY (grade_id) REFERENCES member_grade (grade_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '회원';

CREATE TABLE address (
    address_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    recipient VARCHAR(50) NOT NULL,
    phone VARCHAR(20) NOT NULL COMMENT '연락처(원문, 암호화 추후 적용)',
    zipcode VARCHAR(10) NOT NULL,
    road_address VARCHAR(255) NOT NULL COMMENT '도로명 주소',
    detail_address VARCHAR(255) NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0 COMMENT '기본 배송지',
    PRIMARY KEY (address_id),
    KEY idx_address_member (member_id),
    CONSTRAINT fk_address_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '회원 배송지';

CREATE TABLE admin (
    admin_id BIGINT NOT NULL AUTO_INCREMENT,
    login_id VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(50) NOT NULL,
    role ENUM('SUPER_ADMIN', 'ADMIN') NOT NULL COMMENT 'RBAC 권한',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (admin_id),
    UNIQUE KEY uk_admin_login_id (login_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '관리자';

-- =====================================================================
-- 2. 상품 / 재고
-- =====================================================================

CREATE TABLE category (
    category_id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL COMMENT '상위 카테고리(확장용)',
    name VARCHAR(50) NOT NULL COMMENT '해산물/육류/채소/과일',
    PRIMARY KEY (category_id),
    KEY idx_category_parent (parent_id),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category (category_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '카테고리';

CREATE TABLE supplier (
    supplier_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL COMMENT '공급처',
    contact VARCHAR(100) NULL,
    PRIMARY KEY (supplier_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '공급처';

CREATE TABLE product (
    product_id BIGINT NOT NULL AUTO_INCREMENT,
    product_code VARCHAR(50) NOT NULL COMMENT '자동생성 상품코드',
    name VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    price INT NOT NULL COMMENT '판매가(0 이상)',
    sale_status ENUM(
        'ON_SALE',
        'SOLD_OUT',
        'OFF_SALE'
    ) NOT NULL DEFAULT 'ON_SALE',
    storage_type ENUM('ROOM', 'COLD', 'FROZEN') NOT NULL COMMENT '실온/냉장/냉동',
    min_shelf_life_days INT NOT NULL DEFAULT 0 COMMENT '판매 최소 잔여 유통기한 N',
    description TEXT NULL,
    deleted_at DATETIME NULL COMMENT '소프트딜리트',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (product_id),
    UNIQUE KEY uk_product_code (product_code),
    KEY idx_product_category (category_id),
    KEY idx_product_supplier (supplier_id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category (category_id),
    CONSTRAINT fk_product_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (supplier_id),
    CONSTRAINT chk_product_price CHECK (price >= 0),
    CONSTRAINT chk_product_shelf CHECK (min_shelf_life_days >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '상품';

CREATE TABLE product_image (
    image_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    url VARCHAR(500) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    is_main TINYINT(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (image_id),
    KEY idx_image_product (product_id),
    CONSTRAINT fk_image_product FOREIGN KEY (product_id) REFERENCES product (product_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '상품 이미지';

CREATE TABLE stock_lot (
    lot_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    received_date DATE NOT NULL COMMENT '입고일',
    expiry_date DATE NOT NULL COMMENT '유통기한',
    initial_qty INT NOT NULL COMMENT '입고수량',
    available_qty INT NOT NULL COMMENT '가용재고',
    status ENUM(
        'AVAILABLE',
        'SOLD_OUT',
        'DISPOSED'
    ) NOT NULL DEFAULT 'AVAILABLE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (lot_id),
    KEY idx_lot_supplier (supplier_id),
    -- FEFO 조회 최적화: 상품+상태별 유통기한 임박순
    KEY idx_lot_fefo (
        product_id,
        status,
        expiry_date
    ),
    CONSTRAINT fk_lot_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fk_lot_supplier FOREIGN KEY (supplier_id) REFERENCES supplier (supplier_id),
    CONSTRAINT chk_lot_qty CHECK (
        available_qty >= 0
        AND available_qty <= initial_qty
    )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '입고 로트(실재고 단위)';

-- =====================================================================
-- 3. 장바구니
-- =====================================================================

CREATE TABLE cart (
    cart_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (cart_id),
    UNIQUE KEY uk_cart_member (member_id) COMMENT '1인 1카트',
    CONSTRAINT fk_cart_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '장바구니';

CREATE TABLE cart_item (
    cart_item_id BIGINT NOT NULL AUTO_INCREMENT,
    cart_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    qty INT NOT NULL DEFAULT 1,
    added_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (cart_item_id),
    UNIQUE KEY uk_cart_product (cart_id, product_id) COMMENT '동일상품 중복 방지',
    KEY idx_cartitem_product (product_id),
    CONSTRAINT fk_cartitem_cart FOREIGN KEY (cart_id) REFERENCES cart (cart_id),
    CONSTRAINT fk_cartitem_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT chk_cartitem_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '장바구니 상품';

-- =====================================================================
-- 4. 주문 / 결제
-- =====================================================================

CREATE TABLE orders (
    order_id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(30) NOT NULL COMMENT '주문번호',
    member_id BIGINT NOT NULL,
    status ENUM(
        'PAYMENT_PENDING',
        'PAID',
        'PRODUCT_PREPARING',
        'SHIPMENT_PREPARING',
        'SHIPPING',
        'DELIVERED',
        'CONFIRMED',
        'RETURN_REQUESTED',
        'RETURNED',
        'EXCHANGE_REQUESTED',
        'EXCHANGED',
        'CANCELED'
    ) NOT NULL DEFAULT 'PAYMENT_PENDING',
    product_amount INT NOT NULL COMMENT '총상품금액',
    discount_amount INT NOT NULL DEFAULT 0 COMMENT '쿠폰+등급+포인트',
    shipping_fee INT NOT NULL DEFAULT 0 COMMENT '배송비',
    total_amount INT NOT NULL COMMENT '최종결제금액',
    earned_point INT NOT NULL DEFAULT 0 COMMENT '적립예정포인트',
    ship_recipient VARCHAR(50) NOT NULL COMMENT '배송지 스냅샷',
    ship_phone VARCHAR(20) NOT NULL COMMENT '연락처(원문, 암호화 추후 적용)',
    ship_zipcode VARCHAR(10) NOT NULL,
    ship_address VARCHAR(500) NOT NULL,
    ship_message VARCHAR(255) NULL,
    ordered_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (order_id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_order_member (member_id),
    KEY idx_order_status (status),
    CONSTRAINT fk_order_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '주문(헤더)';

CREATE TABLE order_item (
    order_item_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    name_snapshot VARCHAR(255) NOT NULL COMMENT '주문시점 상품명',
    unit_price INT NOT NULL COMMENT '주문시점 가격',
    qty INT NOT NULL,
    item_status ENUM(
        'ORDERED',
        'CANCELED',
        'RETURN_REQ',
        'RETURNED',
        'EXCHANGE_REQ',
        'EXCHANGED'
    ) NOT NULL DEFAULT 'ORDERED',
    PRIMARY KEY (order_item_id),
    KEY idx_orderitem_order (order_id),
    KEY idx_orderitem_product (product_id),
    CONSTRAINT fk_orderitem_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_orderitem_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT chk_orderitem_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '주문 상품';

CREATE TABLE stock_allocation (
    allocation_id BIGINT NOT NULL AUTO_INCREMENT,
    order_item_id BIGINT NOT NULL,
    lot_id BIGINT NOT NULL,
    qty INT NOT NULL COMMENT 'FEFO 차감 수량',
    allocated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (allocation_id),
    KEY idx_alloc_orderitem (order_item_id),
    KEY idx_alloc_lot (lot_id),
    CONSTRAINT fk_alloc_orderitem FOREIGN KEY (order_item_id) REFERENCES order_item (order_item_id),
    CONSTRAINT fk_alloc_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id),
    CONSTRAINT chk_alloc_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '주문상품-로트 할당(차감 이력)';

CREATE TABLE stock_disposal (
    disposal_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    lot_id BIGINT NULL,
    admin_id BIGINT NOT NULL,
    qty INT NOT NULL COMMENT '폐기수량',
    reason ENUM(
        'EXPIRED',
        'DAMAGED',
        'RETURNED'
    ) NOT NULL COMMENT 'RETURNED=반품/교환 회수품 폐기(신선식품 기본)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (disposal_id),
    KEY idx_disposal_product (product_id),
    KEY idx_disposal_lot (lot_id),
    KEY idx_disposal_admin (admin_id),
    CONSTRAINT fk_disposal_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fk_disposal_lot FOREIGN KEY (lot_id) REFERENCES stock_lot (lot_id),
    CONSTRAINT fk_disposal_admin FOREIGN KEY (admin_id) REFERENCES admin (admin_id),
    CONSTRAINT chk_disposal_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '폐기 이력';

CREATE TABLE order_status_history (
    history_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    from_status VARCHAR(30) NULL,
    to_status VARCHAR(30) NOT NULL,
    changed_by ENUM('USER', 'ADMIN', 'BATCH') NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (history_id),
    KEY idx_history_order (order_id),
    CONSTRAINT fk_history_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '주문 상태 이력';

CREATE TABLE payment (
    payment_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    method ENUM(
        'CARD',
        'TRANSFER',
        'EASY_PAY'
    ) NOT NULL,
    amount INT NOT NULL,
    status ENUM(
        'PENDING',
        'PAID',
        'FAILED',
        'CANCELED',
        'REFUNDED'
    ) NOT NULL DEFAULT 'PENDING',
    pg_tid VARCHAR(100) NULL COMMENT 'PG 거래번호(중복 콜백 멱등성 보장용)',
    payment_due_at DATETIME NULL COMMENT '입금기한 = 주문+24h (무통장입금만 사용, 그 외 NULL)',
    paid_at DATETIME NULL,
    PRIMARY KEY (payment_id),
    UNIQUE KEY uk_payment_order (order_id),
    UNIQUE KEY uk_payment_pg_tid (pg_tid),
    KEY idx_payment_status_due (status, payment_due_at),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '결제';

-- =====================================================================
-- 5. 클레임 (취소 / 반품 / 교환)
-- =====================================================================

CREATE TABLE claim (
    claim_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    type ENUM(
        'CANCEL',
        'RETURN',
        'EXCHANGE'
    ) NOT NULL,
    status ENUM(
        'REQUESTED',
        'APPROVED',
        'REJECTED',
        'COMPLETED'
    ) NOT NULL DEFAULT 'REQUESTED',
    reason_type ENUM('CHANGE_OF_MIND', 'DEFECT') NULL,
    reason VARCHAR(500) NULL,
    processed_by BIGINT NULL COMMENT 'admin (NULL=시스템 자동)',
    requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    PRIMARY KEY (claim_id),
    KEY idx_claim_order (order_id),
    KEY idx_claim_processor (processed_by),
    CONSTRAINT fk_claim_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_claim_admin FOREIGN KEY (processed_by) REFERENCES admin (admin_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '클레임(취소/반품/교환)';

CREATE TABLE claim_item (
    claim_item_id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    qty INT NOT NULL COMMENT '부분 처리 수량',
    PRIMARY KEY (claim_item_id),
    KEY idx_claimitem_claim (claim_id),
    KEY idx_claimitem_orderitem (order_item_id),
    CONSTRAINT fk_claimitem_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id),
    CONSTRAINT fk_claimitem_orderitem FOREIGN KEY (order_item_id) REFERENCES order_item (order_item_id),
    CONSTRAINT chk_claimitem_qty CHECK (qty > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '클레임 대상 상품';

CREATE TABLE refund (
    refund_id BIGINT NOT NULL AUTO_INCREMENT,
    claim_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    amount INT NOT NULL COMMENT '환불액',
    shipping_deduction INT NOT NULL DEFAULT 0 COMMENT '단순변심 배송비 차감',
    status ENUM('PENDING', 'DONE') NOT NULL DEFAULT 'PENDING',
    refunded_at DATETIME NULL,
    PRIMARY KEY (refund_id),
    UNIQUE KEY uk_refund_claim (claim_id),
    KEY idx_refund_payment (payment_id),
    CONSTRAINT fk_refund_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (payment_id),
    CONSTRAINT chk_refund_amount CHECK (amount >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '환불';

CREATE TABLE shipment (
    shipment_id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    claim_id BIGINT NULL COMMENT '반품/교환 회수·재배송 시 연결',
    direction ENUM('OUTBOUND', 'INBOUND') NOT NULL DEFAULT 'OUTBOUND' COMMENT 'OUTBOUND=출고/재배송(창고→고객), INBOUND=회수(고객→창고)',
    carrier VARCHAR(50) NULL,
    tracking_no VARCHAR(50) NULL COMMENT '송장번호',
    status ENUM(
        'PREPARING',
        'SHIPPING',
        'DELIVERED'
    ) NOT NULL DEFAULT 'PREPARING' COMMENT 'INBOUND는 회수준비/회수중/회수완료로 해석',
    shipped_at DATETIME NULL,
    delivered_at DATETIME NULL,
    PRIMARY KEY (shipment_id),
    KEY idx_shipment_order (order_id),
    KEY idx_shipment_claim (claim_id),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT fk_shipment_claim FOREIGN KEY (claim_id) REFERENCES claim (claim_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '배송(정방향 출고/재배송 + 역방향 회수)';

-- =====================================================================
-- 6. 리뷰 / Q&A
-- =====================================================================

CREATE TABLE review (
    review_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    order_item_id BIGINT NOT NULL,
    rating TINYINT NOT NULL COMMENT '1~5',
    title VARCHAR(255) NULL,
    content TEXT NOT NULL,
    is_public TINYINT(1) NOT NULL DEFAULT 1,
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (review_id),
    UNIQUE KEY uk_review_orderitem (order_item_id) COMMENT '구매 건당 1회',
    KEY idx_review_product (product_id),
    KEY idx_review_member (member_id),
    CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fk_review_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_review_orderitem FOREIGN KEY (order_item_id) REFERENCES order_item (order_item_id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '상품 리뷰';

CREATE TABLE qna (
    qna_id BIGINT NOT NULL AUTO_INCREMENT,
    product_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NULL,
    answered_by BIGINT NULL COMMENT 'admin',
    is_public TINYINT(1) NOT NULL DEFAULT 1,
    status ENUM('WAITING', 'ANSWERED') NOT NULL DEFAULT 'WAITING',
    deleted_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (qna_id),
    KEY idx_qna_product (product_id),
    KEY idx_qna_member (member_id),
    KEY idx_qna_admin (answered_by),
    CONSTRAINT fk_qna_product FOREIGN KEY (product_id) REFERENCES product (product_id),
    CONSTRAINT fk_qna_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_qna_admin FOREIGN KEY (answered_by) REFERENCES admin (admin_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '상품 Q&A';

-- =====================================================================
-- 7. 쿠폰 / 포인트
-- =====================================================================

CREATE TABLE coupon (
    coupon_id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    discount_type ENUM('AMOUNT', 'RATE') NOT NULL,
    discount_value INT NOT NULL,
    min_order_amount INT NOT NULL DEFAULT 0 COMMENT '사용조건',
    target_grade_id BIGINT NULL COMMENT '대상 등급(NULL=전체)',
    valid_from DATE NOT NULL,
    valid_to DATE NOT NULL,
    PRIMARY KEY (coupon_id),
    KEY idx_coupon_grade (target_grade_id),
    CONSTRAINT fk_coupon_grade FOREIGN KEY (target_grade_id) REFERENCES member_grade (grade_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '쿠폰 정의';

CREATE TABLE member_coupon (
    member_coupon_id BIGINT NOT NULL AUTO_INCREMENT,
    coupon_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    used_order_id BIGINT NULL,
    status ENUM('ISSUED', 'USED', 'EXPIRED') NOT NULL DEFAULT 'ISSUED',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used_at DATETIME NULL,
    PRIMARY KEY (member_coupon_id),
    KEY idx_mc_coupon (coupon_id),
    KEY idx_mc_member (member_id),
    KEY idx_mc_order (used_order_id),
    CONSTRAINT fk_mc_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (coupon_id),
    CONSTRAINT fk_mc_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_mc_order FOREIGN KEY (used_order_id) REFERENCES orders (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '발급 쿠폰(쿠폰함)';

CREATE TABLE point_history (
    point_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    order_id BIGINT NULL,
    type ENUM('EARN', 'USE', 'EXPIRE') NOT NULL,
    amount INT NOT NULL,
    balance INT NOT NULL COMMENT '잔액',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (point_id),
    KEY idx_point_member (member_id),
    KEY idx_point_order (order_id),
    CONSTRAINT fk_point_member FOREIGN KEY (member_id) REFERENCES member (member_id),
    CONSTRAINT fk_point_order FOREIGN KEY (order_id) REFERENCES orders (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '포인트 내역(원장)';

-- =====================================================================
-- 8. 공통 (알림 / 감사 로그)
-- =====================================================================

CREATE TABLE notification (
    notification_id BIGINT NOT NULL AUTO_INCREMENT,
    member_id BIGINT NOT NULL,
    channel ENUM('EMAIL', 'SMS', 'APP') NOT NULL,
    type ENUM(
        'QNA_ANSWER',
        'ORDER_STATUS',
        'SHIPPING',
        'EXPIRY'
    ) NOT NULL,
    content TEXT NOT NULL,
    status ENUM('SENT', 'FAILED', 'RETRY') NOT NULL DEFAULT 'SENT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (notification_id),
    KEY idx_noti_member (member_id),
    CONSTRAINT fk_noti_member FOREIGN KEY (member_id) REFERENCES member (member_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '알림';

CREATE TABLE audit_log (
    log_id BIGINT NOT NULL AUTO_INCREMENT,
    admin_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL COMMENT 'PRODUCT_DELETE/REFUND/GRADE_CHANGE 등',
    target VARCHAR(100) NULL,
    detail TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id),
    KEY idx_audit_admin (admin_id),
    CONSTRAINT fk_audit_admin FOREIGN KEY (admin_id) REFERENCES admin (admin_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '감사 로그';

SET FOREIGN_KEY_CHECKS = 1;
