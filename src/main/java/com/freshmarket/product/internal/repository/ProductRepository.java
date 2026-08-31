package com.freshmarket.product.internal.repository;

import com.freshmarket.product.internal.entity.Product;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// 기본 CRUD. 동적 조회는 ProductQueryRepository 가 맡는다
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 요청 식별자로 이미 등록된 상품을 찾는다. 재시도 감지에 쓰인다
    Optional<Product> findByRequestId(String requestId);
}