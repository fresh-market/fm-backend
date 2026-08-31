package com.freshmarket.coupon.internal.repository;

import static com.freshmarket.coupon.internal.entity.QCoupon.coupon;

import com.freshmarket.coupon.internal.dto.AdminCouponListRow;
import com.freshmarket.coupon.internal.dto.AdminCouponSearchCondition;
import com.freshmarket.coupon.internal.dto.QAdminCouponListRow;
import com.freshmarket.coupon.internal.entity.CouponScope;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * 관리자가 쿠폰 정의(coupon)를 훑는 동적 조회 전용 컴포넌트.
 *
 * ProductQueryRepository 와 같은 이유로 Spring Data 의 Repository+Impl 자동 결합 관례를 쓰지
 * 않는다(DPB-4-10, 레포지토리 이름 규칙과 충돌).
 *
 * 이름은 조회 대상 엔티티(Coupon)를 따른다. 관리자 전용이라고 Admin 접두사를 붙이지
 * 않는다(DPB-4-09) — 저장 모델은 소비자가 누구든 하나다.
 */
@Repository
@RequiredArgsConstructor
public class CouponQueryRepository {

    private final JPAQueryFactory queryFactory;

    /*
     * AdminProductController 목록과 같은 방식으로 커서 기반 페이지네이션을 쓴다(API-3-04, API-5-01).
     * 정렬이 createdAt desc, id desc 고정이라 정렬축 분기가 필요 없다.
     * pageSize + 1 건을 가져와 다음 페이지 존재 여부를 판단한다.
     */
    public List<AdminCouponListRow> search(AdminCouponSearchCondition condition) {
        return queryFactory
                .select(new QAdminCouponListRow(
                        coupon.id,
                        coupon.name,
                        coupon.scope,
                        coupon.discountType,
                        coupon.discountValue,
                        coupon.maxDiscountAmount,
                        coupon.minOrderAmount,
                        coupon.totalQuantity,
                        coupon.issuedQuantity,
                        coupon.issueStartAt,
                        coupon.issueEndAt,
                        coupon.validFrom,
                        coupon.validTo,
                        coupon.targetGradeId,
                        coupon.active,
                        coupon.createdAt))
                .from(coupon)
                .where(
                        isActiveEq(condition.isActive()),
                        scopeEq(condition.scope()),
                        createdAtCursorLt(condition))
                .orderBy(coupon.createdAt.desc(), coupon.id.desc())
                .limit(condition.pageSize() + 1L)
                .fetch();
    }

    // 활성 여부 필터. null 이면 전체를 본다
    private BooleanExpression isActiveEq(Boolean isActive) {
        return isActive != null ? coupon.active.eq(isActive) : null;
    }

    // 적용 범위 필터. null 이면 전체를 본다
    private BooleanExpression scopeEq(CouponScope scope) {
        return scope != null ? coupon.scope.eq(scope) : null;
    }

    // 커서 조건. 정렬이 createdAt desc, id desc 고정이라 동점 처리는 id desc 하나뿐이다
    private BooleanExpression createdAtCursorLt(AdminCouponSearchCondition condition) {
        if (condition.cursor() == null) {
            return null;
        }
        LocalDateTime cursorCreatedAt = LocalDateTime.parse(condition.cursor().sortValue());
        Long cursorId = condition.cursor().id();
        return coupon.createdAt.lt(cursorCreatedAt)
                .or(coupon.createdAt.eq(cursorCreatedAt).and(coupon.id.lt(cursorId)));
    }
}
