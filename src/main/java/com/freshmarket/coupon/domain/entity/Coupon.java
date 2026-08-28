package com.freshmarket.coupon.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 쿠폰 정의(발급 틀)다. 한정 수량과 마감 시각이 둘 다 있으면 선착순이다.
 * 정책은 한 번 만들면 바뀌지 않는다. 그래서 member_coupon 이 조건을 복사하지 않고 이 행을 참조한다.
 */
@Entity
@Table(name = "coupon")
@AttributeOverride(name = "id", column = @Column(name = "coupon_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Coupon extends BaseMutableTimeEntity {

    private static final int MAX_RATE = 100;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private CouponScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 30)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false)
    private int discountValue;

    // 정률 쿠폰의 할인 상한. 정액 쿠폰에서는 NULL 이다
    @Column(name = "max_discount_amount")
    private Integer maxDiscountAmount;

    @Column(name = "min_order_amount", nullable = false)
    private int minOrderAmount;

    // 발급 한정 수량. NULL 이면 일반 쿠폰이다. 값이 있어도 마감 시각이 없으면 선착순이 아니다
    @Column(name = "total_quantity")
    private Integer totalQuantity;

    /*
     * 몇 장이 나갔는지를 담는 기록이다. 앱은 이 값으로 아무것도 판정하지 않는다.
     * 상한은 member_coupon 의 issue_seq 가 행 단위로 강제하고, 이 값은 종료 배치가 이벤트를
     * 끄면서 실제 행 수로 맞춘다.
     */
    @Column(name = "issued_quantity", nullable = false)
    private int issuedQuantity;

    // 발급 시작 시각. NULL 이면 제한 없음
    @Column(name = "issue_start_at")
    private LocalDateTime issueStartAt;

    // 발급 마감 시각. 선착순 쿠폰은 이 값이 있어야 한다. 없으면 끄는 조건도 Redis 키의 수명도 못 정한다
    @Column(name = "issue_end_at")
    private LocalDateTime issueEndAt;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to", nullable = false)
    private LocalDate validTo;

    // 대상 등급. NULL 이면 전체 회원이다
    @Column(name = "target_grade_id")
    private Long targetGradeId;

    // 발급 스위치. 쿠폰은 초안(FALSE)으로 태어나고 관리자가 이벤트를 열 때 켜진다
    @Column(name = "is_active", nullable = false)
    private boolean active;

    private Coupon(String name, CouponScope scope, DiscountType discountType, int discountValue,
                   LocalDate validFrom, LocalDate validTo,
                   Integer maxDiscountAmount, Integer minOrderAmount, Integer totalQuantity,
                   LocalDateTime issueStartAt, LocalDateTime issueEndAt, Long targetGradeId) {
        validateName(name);
        validateRequired(scope, "scope");
        validateRequired(discountType, "discountType");
        validateDiscount(discountType, discountValue, maxDiscountAmount);
        validateValidPeriod(validFrom, validTo);
        validateMinOrderAmount(minOrderAmount);
        validateTotalQuantity(totalQuantity);
        validateIssuePeriod(issueStartAt, issueEndAt);
        this.name = name;
        this.scope = scope;
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minOrderAmount = (minOrderAmount != null) ? minOrderAmount : 0;
        this.totalQuantity = totalQuantity;
        this.issueStartAt = issueStartAt;
        this.issueEndAt = issueEndAt;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.targetGradeId = targetGradeId;
        this.issuedQuantity = 0;
        this.active = false;
    }

    /*
     * 수량 제한이 없는 일반 쿠폰을 초안으로 만든다. total_quantity 가 비어 있어 순번을 다투지 않는다.
     * 두 팩터리 모두 초안(is_active = FALSE)으로 만들고, 켜는 것은 관리자가 따로 한다.
     * 선택 필드(할인 상한, 최소 주문 금액, 대상 등급)는 그것을 쓰는 기능이 생길 때 오버로딩으로 더한다.
     */
    public static Coupon draftUnlimited(String name, CouponScope scope, DiscountType discountType,
                                        int discountValue, LocalDate validFrom, LocalDate validTo) {
        return new Coupon(name, scope, discountType, discountValue, validFrom, validTo,
                null, null, null, null, null, null);
    }

    /*
     * 선착순 쿠폰을 초안으로 만든다. 한정 수량이 있어야 순번으로 상한을 강제할 수 있다.
     * issueEndAt 도 넣어야 한다. 없으면 isLimited 가 거짓이 되어 이 쿠폰은 선착순 경로에 안 들어온다.
     */
    public static Coupon draftLimited(String name, CouponScope scope, DiscountType discountType,
                                      int discountValue, LocalDate validFrom, LocalDate validTo,
                                      int totalQuantity,
                                      LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
        return new Coupon(name, scope, discountType, discountValue, validFrom, validTo,
                null, null, totalQuantity, issueStartAt, issueEndAt, null);
    }

    /*
     * 선착순 쿠폰인가. 한정 수량과 마감 시각이 둘 다 있어야 선착순이다.
     * 수량이 0 초과인 것은 chk_coupon_quantity 와 validateTotalQuantity 가 이미 보장한다.
     * 마감이 없으면 이벤트를 끄는 조건도 Redis 키의 수명도 걸 기준이 없어, 열리면 네 키가
     * 아무도 못 지우는 채로 남는다. 그래서 마감 없는 수량 쿠폰은 선착순 경로에 안 들어온다.
     */
    public boolean isLimited() {
        return totalQuantity != null && issueEndAt != null;
    }

    /*
     * 발급 기간과 대상 등급을 보는 판정은 이 엔티티에 두지 않는다.
     * 발급 경로가 캐시된 스냅샷으로 판정하므로 그 식은 CachedCoupon 이 갖는다. 같은 식을 양쪽에
     * 두면 한쪽만 고쳤을 때 캐시를 탄 요청과 안 탄 요청의 답이 달라진다.
     */
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
    }

    private static void validateRequired(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 는 필수다");
        }
    }

    // chk_coupon_values, chk_coupon_rate, chk_coupon_max_discount 와 같은 규칙이다
    private static void validateDiscount(DiscountType type, int value, Integer maxDiscountAmount) {
        if (value <= 0) {
            throw new IllegalArgumentException("discountValue 는 0 보다 커야 한다: " + value);
        }
        if (type == DiscountType.RATE && value > MAX_RATE) {
            throw new IllegalArgumentException("정률 할인은 100 을 넘을 수 없다: " + value);
        }
        if (type == DiscountType.AMOUNT && maxDiscountAmount != null) {
            throw new IllegalArgumentException("정액 할인에는 상한을 둘 수 없다");
        }
        if (maxDiscountAmount != null && maxDiscountAmount <= 0) {
            throw new IllegalArgumentException("maxDiscountAmount 는 0 보다 커야 한다: " + maxDiscountAmount);
        }
    }

    // chk_coupon_valid_period 와 같은 규칙이다
    private static void validateValidPeriod(LocalDate validFrom, LocalDate validTo) {
        validateRequired(validFrom, "validFrom");
        validateRequired(validTo, "validTo");
        if (validFrom.isAfter(validTo)) {
            throw new IllegalArgumentException("validFrom 은 validTo 보다 뒤일 수 없다");
        }
    }

    private static void validateMinOrderAmount(Integer minOrderAmount) {
        if (minOrderAmount != null && minOrderAmount < 0) {
            throw new IllegalArgumentException("minOrderAmount 는 0 이상이어야 한다: " + minOrderAmount);
        }
    }

    // chk_coupon_quantity 와 같은 규칙이다
    private static void validateTotalQuantity(Integer totalQuantity) {
        if (totalQuantity != null && totalQuantity <= 0) {
            throw new IllegalArgumentException("totalQuantity 는 0 보다 커야 한다: " + totalQuantity);
        }
    }

    // chk_coupon_issue_period 와 같은 규칙이다
    private static void validateIssuePeriod(LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
        if (issueStartAt != null && issueEndAt != null && issueEndAt.isBefore(issueStartAt)) {
            throw new IllegalArgumentException("issueEndAt 은 issueStartAt 보다 앞설 수 없다");
        }
    }
}
