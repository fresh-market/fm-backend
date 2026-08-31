package com.freshmarket.coupon.internal.repository;

import static com.freshmarket.coupon.internal.entity.QMemberCoupon.memberCoupon;

import com.freshmarket.coupon.internal.dto.AdminMemberCouponListRow;
import com.freshmarket.coupon.internal.dto.AdminMemberCouponSearchCondition;
import com.freshmarket.coupon.internal.dto.QAdminMemberCouponListRow;
import com.freshmarket.coupon.internal.entity.MemberCouponStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * 관리자가 쿠폰 한 장의 발급 이력을 훑는 동적 조회 전용 컴포넌트.
 *
 * ProductQueryRepository 와 같은 이유로 Spring Data 의 Repository+Impl 자동 결합 관례를 쓰지
 * 않는다(DPB-4-10, 레포지토리 이름 규칙과 충돌).
 *
 * 이름은 조회 대상 엔티티(MemberCoupon)를 따른다. 관리자 전용이라고 Admin 접두사를 붙이지
 * 않는다(DPB-4-09) — 저장 모델은 소비자가 누구든 하나다.
 */
@Repository
@RequiredArgsConstructor
public class MemberCouponQueryRepository {

    private final JPAQueryFactory queryFactory;

    /*
     * 발급 순서(issuedAt desc, id desc)로 페이지를 자른다.
     * pageSize + 1 건을 가져와 다음 페이지 존재 여부를 판단한다(API-5-01).
     */
    public List<AdminMemberCouponListRow> searchIssues(AdminMemberCouponSearchCondition condition) {
        return queryFactory
                .select(new QAdminMemberCouponListRow(
                        memberCoupon.id,
                        memberCoupon.memberId,
                        memberCoupon.issueSeq,
                        memberCoupon.status,
                        memberCoupon.issuedAt,
                        memberCoupon.usedAt))
                .from(memberCoupon)
                .where(
                        memberCoupon.couponId.eq(condition.couponId()),
                        statusEq(condition.status()),
                        issuedAtCursorLt(condition))
                .orderBy(memberCoupon.issuedAt.desc(), memberCoupon.id.desc())
                .limit(condition.pageSize() + 1L)
                .fetch();
    }

    // 상태 필터. null 이면 전체 상태를 본다
    private BooleanExpression statusEq(MemberCouponStatus status) {
        return status != null ? memberCoupon.status.eq(status) : null;
    }

    // 커서 조건. 정렬이 issuedAt desc, id desc 고정이라 동점 처리는 id desc 하나뿐이다
    private BooleanExpression issuedAtCursorLt(AdminMemberCouponSearchCondition condition) {
        if (condition.cursor() == null) {
            return null;
        }
        LocalDateTime cursorIssuedAt = LocalDateTime.parse(condition.cursor().sortValue());
        Long cursorId = condition.cursor().id();
        return memberCoupon.issuedAt.lt(cursorIssuedAt)
                .or(memberCoupon.issuedAt.eq(cursorIssuedAt).and(memberCoupon.id.lt(cursorId)));
    }
}
