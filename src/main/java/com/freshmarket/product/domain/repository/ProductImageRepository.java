package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.UploadStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// ProductImage 엔티티에 대한 조회/저장을 담당한다
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    // 완료 통지가 이 값으로 행을 찾는다(리소스 식별자가 아니라 발급자 확인용. ProductImage.uploadId 주석 참고)
    Optional<ProductImage> findByUploadId(UUID uploadId);

    // 이 이미지가 실제로 그 상품 소속인지 함께 확인한다. 경로의 productId와 imageId가 서로 다른 상품을 가리키는 것을 막는다
    Optional<ProductImage> findByIdAndProductId(Long id, Long productId);

    /*
     * confirm()의 상태 전이를 원자적으로 수행한다(DI-4-02). S3 확인은 트랜잭션 밖에서 이미 끝낸
     * 상태라, 그 사이 값이 바뀌었을 수 있다(동시 확정, 확정 중 삭제) — PENDING 조건과 함께 갱신해
     * 그 경우 영향받은 행이 0이 되게 한다(AdminProductImageService.confirmOrThrow 참고).
     */
    @Modifying
    @Query("update ProductImage i set i.uploadStatus = :confirmed where i.id = :id and i.uploadStatus = :pending")
    int confirmIfPending(@Param("id") Long id, @Param("pending") UploadStatus pending,
            @Param("confirmed") UploadStatus confirmed);

    /*
     * delete()의 행 삭제를 원자적으로 수행한다(DI-4-02). S3 삭제(멱등)는 트랜잭션 밖에서 이미
     * 끝낸 상태다 — 이 시점에 행이 이미 없어도(영향받은 행 0) 목표 상태(행 없음)에 도달한 것이므로
     * AdminProductImageService에서 오류로 보지 않는다.
     */
    @Modifying
    @Query("delete from ProductImage i where i.id = :id and i.productId = :productId")
    int deleteByIdAndProductId(@Param("id") Long id, @Param("productId") Long productId);

    // 상품 상세 조회에서 노출할 확정된 이미지 목록을 가져온다
    List<ProductImage> findByProductIdAndUploadStatus(Long productId, UploadStatus uploadStatus);

    /*
     * (INF-11-13) 정리 배치의 대상 조회. 유예 시간을 넘긴 PENDING 행을 id 기준 keyset 페이지네이션으로
     * 나눠 가져온다(OptionAvailabilitySyncRetryService.retryAllPending()과 같은 이유 — findAll()로
     * 한 번에 올리지 않는다). status·created_at 복합 인덱스가 필요하다(V17 마이그레이션).
     */
    List<ProductImage> findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc(
            UploadStatus uploadStatus, Long afterId, LocalDateTime cutoff, Pageable pageable);

    /*
     * 정리 배치의 행 삭제를 원자적으로 수행한다(INF-1-04). "PENDING일 때만" 조건부라, 그 사이
     * 관리자가 confirm()한 행을 배치가 지우는 일이 없다(영향받은 행 0이면 건드리지 않은 것).
     */
    @Modifying
    @Query("delete from ProductImage i where i.id = :id and i.uploadStatus = :pending")
    int deleteByIdAndUploadStatus(@Param("id") Long id, @Param("pending") UploadStatus pending);

    /*
     * (API-5-07) 업로드 URL 발급 재시도 감지. requestId는 DB 전역에서 유일하다(uk_product_image_
     * request_id). productId까지 같이 봐서, 클라이언트가 같은 requestId를 다른 상품에 잘못
     * 재사용해도 엉뚱한 상품의 이미지를 재시도 응답으로 돌려주지 않는다(StockLotRepository.
     * findByRequestIdAndProductOptionId와 같은 이유).
     */
    Optional<ProductImage> findByRequestIdAndProductId(String requestId, Long productId);
}
