package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.UploadStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// ProductImage 엔티티에 대한 조회/저장을 담당한다
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /*
     * 완료 통지가 이 값으로 행을 찾는다(리소스 식별자가 아니라 발급자 확인용. ProductImage.uploadId
     * 주석 참고). 쓰기 락을 건다 — 같은 uploadId로 confirm()이 동시에 두 번 들어오거나, 확정
     * 처리 중에 같은 이미지를 delete()가 지우는 경합을 막는다(AdminLotService의
     * findByIdForUpdate와 같은 이유).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductImage i where i.uploadId = :uploadId")
    Optional<ProductImage> findByUploadIdForUpdate(@Param("uploadId") UUID uploadId);

    /*
     * 이 이미지가 실제로 그 상품 소속인지 함께 확인한다. 경로의 productId와 imageId가 서로 다른
     * 상품을 가리키는 것을 막는다. 쓰기 락을 건다 — 삭제 중인 이미지를 동시에 confirm()하는
     * 경합을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductImage i where i.id = :id and i.productId = :productId")
    Optional<ProductImage> findByIdAndProductIdForUpdate(@Param("id") Long id, @Param("productId") Long productId);

    // 상품 상세 조회에서 노출할 확정된 이미지 목록을 가져온다
    List<ProductImage> findByProductIdAndUploadStatus(Long productId, UploadStatus uploadStatus);

    /*
     * (API-5-07) 업로드 URL 발급 재시도 감지. requestId는 DB 전역에서 유일하다(uk_product_image_
     * request_id). productId까지 같이 봐서, 클라이언트가 같은 requestId를 다른 상품에 잘못
     * 재사용해도 엉뚱한 상품의 이미지를 재시도 응답으로 돌려주지 않는다(StockLotRepository.
     * findByRequestIdAndProductOptionId와 같은 이유).
     */
    Optional<ProductImage> findByRequestIdAndProductId(String requestId, Long productId);
}
