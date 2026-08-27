package com.freshmarket.coupon.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * 회원에게 나간 실제 쿠폰이다.
 * 발급 조건을 복사하지 않고 coupon 을 참조한다. 정책이 불변이라 복사할 이유가 없다.
 * 상태를 바꾸는 메서드를 두지 않는다. 전이는 조건부 UPDATE 한 문장으로 하며 근거는 coupon.md 2장에 있다.
 */
@Entity
@Table(name = "member_coupon")
@AttributeOverride(name = "id", column = @Column(name = "member_coupon_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberCoupon extends BaseMutableTimeEntity {

    @Column(name = "coupon_id", nullable = false)
    private Long couponId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 복합 외래 키의 한 칸이다. 스냅샷이 아니라 coupon.scope 와 같기를 강제하는 역할이다
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private CouponScope scope;

    /*
     * 발급 시점의 coupon.total_quantity 다. 표시용 스냅샷이 아니라 상한을 지키는 장치다.
     * CHECK 은 다른 테이블을 못 보므로 이 값이 행에 있어야 chk_mc_issue_seq 가 성립한다.
     */
    @Column(name = "issue_limit")
    private Integer issueLimit;

    // 선착순 발급 순번(1 부터). 무제한 쿠폰은 NULL 이다
    @Column(name = "issue_seq")
    private Integer issueSeq;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private MemberCouponStatus status;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    private MemberCoupon(Long couponId, Long memberId, CouponScope scope,
                         Integer issueLimit, Integer issueSeq) {
        validateRequired(couponId, "couponId");
        validateRequired(memberId, "memberId");
        validateRequired(scope, "scope");
        validateIssueSeq(issueLimit, issueSeq);
        this.couponId = couponId;
        this.memberId = memberId;
        this.scope = scope;
        this.issueLimit = issueLimit;
        this.issueSeq = issueSeq;
        this.status = MemberCouponStatus.ISSUED;
        this.issuedAt = LocalDateTime.now();
    }

    // 수량 제한이 없는 발급. 순번을 다투지 않으므로 issueLimit 과 issueSeq 가 없다
    public static MemberCoupon issue(Long couponId, Long memberId, CouponScope scope) {
        return new MemberCoupon(couponId, memberId, scope, null, null);
    }

    // 선착순 발급. 순번은 순번 발급기가 준 값을 그대로 쓴다
    public static MemberCoupon issue(Long couponId, Long memberId, CouponScope scope,
                                     int issueLimit, int issueSeq) {
        return new MemberCoupon(couponId, memberId, scope, issueLimit, issueSeq);
    }

    // 선착순으로 나간 발급분인가
    public boolean isLimited() {
        return issueSeq != null;
    }

    private static void validateRequired(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " 는 필수다");
        }
    }

    /*
     * chk_mc_issue_seq 와 같은 규칙이다.
     * 둘 다 있거나 둘 다 없어야 하고, 있으면 1 부터 issueLimit 사이여야 한다.
     * DB 가 최종 방어선이지만 앱에서 먼저 걸러야 원인을 알아보기 쉽다.
     */
    private static void validateIssueSeq(Integer issueLimit, Integer issueSeq) {
        if (issueLimit == null && issueSeq == null) {
            return;
        }
        if (issueLimit == null || issueSeq == null) {
            throw new IllegalArgumentException("issueLimit 과 issueSeq 는 함께 있거나 함께 없어야 한다");
        }
        if (issueSeq < 1 || issueSeq > issueLimit) {
            throw new IllegalArgumentException(
                    "issueSeq 는 1 부터 issueLimit 사이여야 한다: " + issueSeq + "/" + issueLimit);
        }
    }
}
