package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    // 회원 조회용. OFF_SALE(판매안함) 옵션은 제외한다. 품절은 표시만 하고 노출은 유지한다
    List<ProductOption> findByProductIdAndSaleStatusNot(Long productId, SaleStatus saleStatus);

    // 상품 하나에 속한 옵션 전체를 찾는다. 재시도 응답을 재구성할 때 쓰인다
    List<ProductOption> findAllByProductId(Long productId);

    // 옵션이 존재하기만 하는지가 아니라 그 productId 소속인지까지 확인한다. ProductApi가 이걸 그대로 노출한다
    boolean existsByIdAndProductId(Long id, Long productId);
}