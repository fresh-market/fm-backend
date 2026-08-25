package com.freshmarket.product.domain.batch;

import com.freshmarket.product.domain.client.ImageStorageClient;
import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.UploadStatus;
import com.freshmarket.product.domain.repository.ProductImageRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.exception.SdkException;

/*
 * (INF-11-13) 확정 통지가 유실되면 아무도 참조하지 않는 PENDING 행과 S3 객체가 남는다. 조회는
 * 확정된 행만 걸러내므로(INF-11-09) 사용자 눈에 안 보이고, 그래서 INF-11-08의 삭제 경로(사용자가
 * 직접 지우는 것)에도 걸리지 않는다. 이 배치가 유예 시간을 넘긴 PENDING 행을 찾아 S3 객체를 먼저
 * 지운 뒤 행을 지운다 — 행을 먼저 지우면 키를 잃어 객체를 영영 못 찾는다(INF-11-08과 같은 순서).
 *
 * 배치가 전용 서버 한 대에서만 돌고(INF-1 서두), S3 DeleteObject(없는 객체에도 성공)와 행 삭제
 * (deleteByIdAndUploadStatus, "PENDING일 때만" 조건부)가 둘 다 멱등이라, 중간에 죽고 다시 돌아도
 * 같은 행을 다시 시도하는 것만으로 안전하다 — 외부 호출 전에 별도로 상태를 선점할 필요가 없다
 * (INF-1-06의 "다른 인스턴스가 같은 행을 집어 드는" 상황 자체가, 배치 서버가 한 대뿐이라 생기지 않는다).
 *
 * 유예 시간은 등급 C 추정치다(upload.product-image.max-size-bytes 주석과 같은 상태) — presigned URL
 * 자체는 5분(s3.presigned-url-expiry-seconds)이면 만료되지만, 업로드는 그 안에 성공하고도 확정
 * 통지만 유실될 수 있어 더 넉넉히 잡는다. INF-11-12(조회 시점 확정)는 이 코드베이스에 없으므로
 * 대상 밖이다.
 */
@Slf4j
@Service
public class PendingProductImageCleanupService {

    private static final int PAGE_SIZE = 200;

    private final ProductImageRepository productImageRepository;
    private final ImageStorageClient imageStorageClient;
    private final Clock clock;
    private final Duration gracePeriod;

    public PendingProductImageCleanupService(ProductImageRepository productImageRepository,
            ImageStorageClient imageStorageClient, Clock clock,
            @Value("${upload.product-image.pending-cleanup-grace-hours:24}") long graceHours) {
        this.productImageRepository = productImageRepository;
        this.imageStorageClient = imageStorageClient;
        this.clock = clock;
        this.gracePeriod = Duration.ofHours(graceHours);
    }

    public void cleanupExpiredPending() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minus(gracePeriod);
        Long afterId = 0L;
        List<ProductImage> page;
        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        do {
            page = productImageRepository.findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc(
                    UploadStatus.PENDING, afterId, cutoff, pageable);
            for (ProductImage image : page) {
                cleanupOne(image);
                afterId = image.getId();
            }
        } while (!page.isEmpty());
    }

    /*
     * 한 건의 S3 삭제 실패가 이번 주기의 나머지 대상까지 멈추지 않는다 — 실패한 행은 PENDING과
     * created_at을 그대로 유지하므로 다음 주기에 다시 대상이 된다(OptionAvailabilitySyncRetryService.
     * retryOne()과 같은 이유).
     */
    private void cleanupOne(ProductImage image) {
        try {
            imageStorageClient.deleteObjectOrThrow(image.getObjectKey());
        } catch (SdkException e) {
            log.error("event=IMAGE_PENDING_CLEANUP_OBJECT_FAILED imageId={} objectKey={}",
                    image.getId(), image.getObjectKey(), e);
            return;
        }
        productImageRepository.deleteByIdAndUploadStatus(image.getId(), UploadStatus.PENDING);
    }
}
