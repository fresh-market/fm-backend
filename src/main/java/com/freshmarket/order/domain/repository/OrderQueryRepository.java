package com.freshmarket.order.domain.repository;

import static com.freshmarket.order.domain.entity.QOrder.order;

import com.freshmarket.order.domain.dto.OrderSearchCondition;
import com.freshmarket.order.domain.entity.Order;
import com.freshmarket.order.domain.entity.OrderStatus;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

// 주문 목록의 선택 필터와 페이지 처리를 QueryDSL로 조립하는 조회 전용 컴포넌트다.
@Repository
@RequiredArgsConstructor
public class OrderQueryRepository {

    private final JPAQueryFactory queryFactory;

    public Page<Order> findAllByMemberIdAndCondition(Long memberId, OrderSearchCondition condition,
                                                      Pageable pageable) {
        List<Order> orders = queryFactory
                .selectFrom(order)
                .where(
                        order.memberId.eq(memberId),
                        statusEq(condition.status()),
                        orderedAtGoe(condition.from()),
                        orderedAtLt(condition.to()))
                .orderBy(order.orderedAt.desc(), order.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory
                .select(order.count())
                .from(order)
                .where(
                        order.memberId.eq(memberId),
                        statusEq(condition.status()),
                        orderedAtGoe(condition.from()),
                        orderedAtLt(condition.to()))
                .fetchOne();

        return new PageImpl<>(orders, pageable, total == null ? 0 : total);
    }

    private BooleanExpression statusEq(OrderStatus status) {
        return status == null ? null : order.status.eq(status);
    }

    private BooleanExpression orderedAtGoe(LocalDate from) {
        return from == null ? null : order.orderedAt.goe(from.atStartOfDay());
    }

    private BooleanExpression orderedAtLt(LocalDate to) {
        return to == null ? null : order.orderedAt.lt(to.plusDays(1).atStartOfDay());
    }
}
