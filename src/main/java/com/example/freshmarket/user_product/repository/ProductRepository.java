package com.example.freshmarket.user_product.repository;

import com.example.freshmarket.user_product.domain.Product;
import com.example.freshmarket.user_product.domain.SaleStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    // 카테고리 별 상품 조회 (판매 중인 상품만)
    Page<Product> findAllBySaleStatusAndCategory_IdAndDeletedAtIsNull(
            SaleStatus saleStatus, Long categoryId, Pageable pageable
    );

    // 판매 중인 전체 상품 조회 (메인 페이지 용)
    Page<Product> findAllBySaleStatusAndDeletedAtIsNull(
            SaleStatus saleStatus, Pageable pageable
    );

    // 상품명으로 조회(판매 중인 상품만)
    Page<Product> findAllByNameContainingAndSaleStatusAndDeletedAtIsNull (
            String keyword, SaleStatus saleStatus, Pageable pageable
    );

    // 상품 상세 조회 (카테고리, 공급처 함께 조회 - fetch join 효과)
    // @EntityGraph: 내부적으로 Hibernate가 fetch join으로 변환해 실행
    // @Override: findById를 오버라이드 하면 ProductRepository를 통해 호출하는 모든 findById 호출에 항상 category, supplier가 함께 로딩됨
    @EntityGraph(attributePaths = {"category", "supplier"})
    @Override
    Optional<Product> findById(Long id);
}