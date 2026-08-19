package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.ProductImage;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

// ProductImage 엔티티에 대한 조회/저장을 담당한다
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    // 완료 통지가 이 값으로 행을 찾는다(리소스 식별자가 아니라 발급자 확인용. ProductImage.uploadId 주석 참고)
    Optional<ProductImage> findByUploadId(UUID uploadId);

    // 이 이미지가 실제로 그 상품 소속인지 함께 확인한다. 경로의 productId와 imageId가 서로 다른 상품을 가리키는 것을 막는다
    Optional<ProductImage> findByIdAndProductId(Long id, Long productId);
}
