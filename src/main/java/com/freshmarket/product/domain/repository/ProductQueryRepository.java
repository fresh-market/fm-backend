package com.freshmarket.product.domain.repository;

import static com.freshmarket.product.domain.entity.QCategory.category;
import static com.freshmarket.product.domain.entity.QProduct.product;
import static com.freshmarket.product.domain.entity.QProductOption.productOption;

import com.freshmarket.product.domain.dto.ProductSearchCondition;
import com.freshmarket.product.domain.dto.ProductSortType;
import com.freshmarket.product.domain.dto.ProductWithMinPrice;
import com.freshmarket.product.domain.dto.QProductWithMinPrice;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.NumberExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/*
 * QueryDSL 로 짜는 상품 동적 조회 전용 컴포넌트.
 *
 * Spring Data 의 <Repository 인터페이스명>+Impl 자동 결합 관례를 쓰지 않는다.
 * 그 관례를 쓰면 Impl 접미사가 붙어 이 팀의 레포지토리 이름 규칙(DPB-4-10,
 * ArchitectureTest 의 레포지토리_이름)과 충돌한다. 그냥 일반 빈으로 등록해 우회한다.
 */
@Repository
@RequiredArgsConstructor
public class ProductQueryRepository {

    // 옵션 최저가. OFF_SALE(비활성) 옵션만 제외한다. SOLD_OUT 은 화면에 노출하되
    // 품절 표시(soldOut)로 구분해야 하므로 여기서 걸러내지 않는다
    private static final NumberExpression<Integer> MIN_PRICE = productOption.price.min();

    /*
     * 상품 품절 여부. 조인된 옵션(OFF_SALE 제외) 중 sold_out=false(0)인 옵션이 하나라도 있으면
     * MIN이 0이 되어 false다 — 구매 가능한 옵션이 남아있다는 뜻이다. 전부 품절(1)이어야 true.
     */
    private static final BooleanExpression ALL_OPTIONS_SOLD_OUT =
            Expressions.numberTemplate(Integer.class, "min({0})", productOption.soldOut).eq(1);

    private final JPAQueryFactory queryFactory;

    /*
     * 조건에 맞는 상품을 옵션 최저가와 함께 조회한다. 목록 조회와 검색(:search)이 이 메서드를
     * 공유한다 — query 조건이 있고 없고 차이뿐이라 서비스/리포지토리 로직은 동일하다.
     * pageSize + 1 건을 가져와 다음 페이지 존재 여부를 판단할 수 있게 한다.
     */
    public List<ProductWithMinPrice> search(ProductSearchCondition condition) {
        return queryFactory
                .select(new QProductWithMinPrice(
                        product.id,
                        product.name,
                        category.id,
                        category.name,
                        MIN_PRICE,
                        product.saleStatus,
                        ALL_OPTIONS_SOLD_OUT,
                        product.createdAt))
                .from(product)
                .join(category).on(category.id.eq(product.categoryId))
                .join(productOption).on(
                        productOption.productId.eq(product.id),
                        productOption.saleStatus.ne(SaleStatus.OFF_SALE))
                .where(
                        product.deletedAt.isNull(),
                        categoryIdEq(condition.categoryId()),
                        priceGoe(condition.minPriceKrw()),
                        priceLoe(condition.maxPriceKrw()),
                        nameContains(condition.query()),
                        createdAtCursorLt(condition))
                .groupBy(product.id, product.name, category.id, category.name,
                        product.saleStatus, product.createdAt)
                .having(priceCursor(condition))
                .orderBy(orderOf(condition))
                .limit(condition.pageSize() + 1L)
                .fetch();
    }

    // 카테고리 조건. null 이면 where 절에서 빠져 전체 카테고리를 본다
    private BooleanExpression categoryIdEq(Long categoryId) {
        return categoryId != null ? product.categoryId.eq(categoryId) : null;
    }

    /*
     * 가격 하한. 옵션 단위로 거른다 — "이 가격대 옵션이 있는 상품"을 찾는 필터다.
     * where 에 두므로, 조건에 안 맞는 옵션은 이 상품의 그룹에서 제외된 채 최저가가 계산된다.
     * 즉 응답의 minPrice 는 "조건을 만족하는 옵션들 중 최저가"이며, 상품의 절대 최저가와
     * 다를 수 있다. (예: 4만원 이상 필터 시, 1kg=12900원 옵션이 있어도 상품 자체는 노출되고
     * minPrice 는 조건을 만족하는 옵션 중 최저값으로 계산된다)
     */
    private BooleanExpression priceGoe(Integer minPriceKrw) {
        return minPriceKrw != null ? productOption.price.goe(minPriceKrw) : null;
    }

    // 가격 상한. 이유는 위와 같다
    private BooleanExpression priceLoe(Integer maxPriceKrw) {
        return maxPriceKrw != null ? productOption.price.loe(maxPriceKrw) : null;
    }

    /*
     * 상품명 부분 일치 검색 (:search 전용). QueryDSL 이 파라미터 바인딩으로 처리하므로
     * 검색어를 문자열로 직접 이어붙이지 않는다 (SEC-2-01).
     * LIKE 와일드카드(%, _)를 사용자가 그대로 입력하면 의도치 않은 매칭이 되므로 이스케이프한다.
     */
    private BooleanExpression nameContains(String query) {
        if (query == null) {
            return null;
        }
        String escaped = query.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return product.name.like("%" + escaped + "%", '\\');
    }

    /*
     * created_at 계열 정렬(CREATED_DESC, SALES_DESC)의 커서 조건.
     * product.createdAt 은 상품마다 하나뿐인 값이라 그룹화 전 WHERE 에서 걸러도 결과가 같다.
     * 내림차순이라 "다음 페이지"는 커서보다 작은 값이고, 값이 같으면 id 로 가른다
     * (동점 처리 규칙은 orderOf() 의 tie-break, product.id.desc() 와 짝을 맞춘다).
     */
    private BooleanExpression createdAtCursorLt(ProductSearchCondition condition) {
        if (isPriceSort(condition.sort()) || condition.cursor() == null) {
            return null;
        }
        LocalDateTime cursorCreatedAt = LocalDateTime.parse(condition.cursor().sortValue());
        Long cursorId = condition.cursor().id();
        return product.createdAt.lt(cursorCreatedAt)
                .or(product.createdAt.eq(cursorCreatedAt).and(product.id.lt(cursorId)));
    }

    /*
     * 가격 계열 정렬(PRICE_ASC, PRICE_DESC)의 커서 조건.
     * MIN_PRICE 는 그룹화 결과에서만 값을 아는 집계라 HAVING 에 둔다.
     * product.id 는 groupBy 대상이라 HAVING 에서도 참조할 수 있다.
     * 오름차순은 "다음 페이지"가 커서보다 큰 값, 내림차순은 작은 값이다.
     */
    private BooleanExpression priceCursor(ProductSearchCondition condition) {
        if (!isPriceSort(condition.sort()) || condition.cursor() == null) {
            return null;
        }
        int cursorPrice = Integer.parseInt(condition.cursor().sortValue());
        Long cursorId = condition.cursor().id();
        BooleanExpression primary = condition.sort() == ProductSortType.PRICE_ASC
                ? MIN_PRICE.gt(cursorPrice)
                : MIN_PRICE.lt(cursorPrice);
        return primary.or(MIN_PRICE.eq(cursorPrice).and(product.id.lt(cursorId)));
    }

    private boolean isPriceSort(ProductSortType sort) {
        return sort == ProductSortType.PRICE_ASC || sort == ProductSortType.PRICE_DESC;
    }

    /*
     * 정렬 기준. id 내림차순을 항상 마지막에 붙인다.
     * 정렬 값이 같은 행들의 순서가 실행마다 흔들리면 커서 페이징에서 행이 새거나 중복된다.
     *
     * SALES_DESC 는 daily_sales 가 필요해 statistics 도메인 도입 전까지 CREATED_DESC 로 처리한다.
     */
    private OrderSpecifier<?>[] orderOf(ProductSearchCondition condition) {
        return switch (condition.sort()) {
            case PRICE_ASC -> new OrderSpecifier<?>[]{MIN_PRICE.asc(), product.id.desc()};
            case PRICE_DESC -> new OrderSpecifier<?>[]{MIN_PRICE.desc(), product.id.desc()};
            case CREATED_DESC, SALES_DESC ->
                    new OrderSpecifier<?>[]{product.createdAt.desc(), product.id.desc()};
        };
    }
}