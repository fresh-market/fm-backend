package com.freshmarket.product.domain;

import static com.freshmarket.common.query.CollationExpressions.equalsAsCs;
import static com.freshmarket.product.domain.entity.QProduct.product;
import static com.freshmarket.product.domain.entity.QProductOption.productOption;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.product.domain.dto.ProductOptionProjection;
import com.freshmarket.product.domain.dto.QProductOptionProjection;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

// ProductApi 구현체. package-private로 감춰 다른 도메인이 이 클래스를 직접 참조하지 못하게 한다
@Component
class ProductApiImpl implements ProductApi {

    private final ProductOptionRepository productOptionRepository;
    private final JPAQueryFactory queryFactory;

    ProductApiImpl(ProductOptionRepository productOptionRepository, JPAQueryFactory queryFactory) {
        this.productOptionRepository = productOptionRepository;
        this.queryFactory = queryFactory;
    }

    @Override
    public boolean existsOption(Long productId, Long optionId) {
        return productOptionRepository.existsByIdAndProductId(optionId, productId);
    }

    // 재시도 응답 재구성용으로 이미 있는 findAllByProductId를 그대로 재사용해 ID만 뽑는다
    @Override
    public List<Long> findOptionIds(Long productId) {
        return productOptionRepository.findAllByProductId(productId).stream()
                .map(ProductOption::getId)
                .toList();
    }

    @Override
    public Optional<ProductOptionInfo> findOptionInfo(Long productOptionId) {
        return findOptionInfos(List.of(productOptionId)).stream().findFirst();
    }

    /*
     * product + product_option 을 join 해 여러 옵션을 한 번에 가져온다.
     * sale_status 컬럼이 COLLATE utf8mb4_0900_as_cs 라 .eq() 를 그냥 쓰면 콜레이션
     * 충돌이 난다. CollationExpressions.equalsAsCs() 로 우회한다.
     */
    @Override
    public List<ProductOptionInfo> findOptionInfos(List<Long> productOptionIds) {
        if (productOptionIds == null || productOptionIds.isEmpty()) {
            return List.of();
        }
        List<ProductOptionProjection> rows = queryFactory
                .select(new QProductOptionProjection(
                        product.id,
                        product.categoryId,
                        productOption.id,
                        product.name,
                        productOption.name,
                        productOption.price,
                        product.deletedAt.isNull()
                                .and(equalsAsCs(productOption.saleStatus, SaleStatus.ON_SALE)),
                        product.saleAvailableDaysFromExpiry))
                .from(productOption)
                .join(product).on(product.id.eq(productOption.productId))
                .where(productOption.id.in(productOptionIds))
                .fetch();

        return rows.stream()
                .map(row -> new ProductOptionInfo(
                        row.productId(), row.categoryId(), row.productOptionId(),
                        row.productName(), row.optionName(), row.price(), row.purchasable(),
                        row.saleAvailableDaysFromExpiry()))
                .toList();
    }
}
