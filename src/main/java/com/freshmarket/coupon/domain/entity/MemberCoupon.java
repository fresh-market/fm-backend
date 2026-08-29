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
 *
 * 이 클래스는 매핑만 맡는다. 발급 행을 만드는 것은 MemberCouponBulkRepository 의 JDBC 배치이고
 * 상태를 옮기는 것은 MemberCouponRepository 의 네이티브 UPDATE 라, 아무도 이 엔티티를 만들지 않는다.
 * 그래도 남기는 이유는 ddl-auto: validate 가 이 필드들을 실제 표와 대조해 스키마가 어긋나면 기동을 막기 때문이다.
 *
 * 순번이 1 과 issue_limit 사이인지는 순번 확보 스크립트(coupon-issue-seq.lua)가 INCR 결과를 상한과
 * 견주어 지키고, chk_mc_issue_seq 가 최종 방어선으로 한 번 더 본다.
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
}
