package com.freshmarket.product.internal.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.internal.client.ImageStorageClient;
import com.freshmarket.product.internal.entity.ProductImage;
import com.freshmarket.product.internal.entity.UploadStatus;
import com.freshmarket.product.internal.repository.ProductImageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;

/*
 * ProductImageRepository/ImageStorageClient/Clock 전부 mock 또는 고정값이다(UT-4-01).
 * OptionAvailabilitySyncRetryServiceTest와 같은 스타일 — keyset 페이지네이션과 부분 실패 격리를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PendingProductImageCleanupServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 25, 12, 0);
    private static final long GRACE_HOURS = 24;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private ImageStorageClient imageStorageClient;

    private PendingProductImageCleanupService sut;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        sut = new PendingProductImageCleanupService(productImageRepository, imageStorageClient, clock, GRACE_HOURS);
    }

    private static ProductImage imageFixture(Long id, String objectKey) {
        ProductImage image = ProductImage.register(1L, "req-" + id, objectKey);
        ReflectionTestUtils.setField(image, "id", id);
        ReflectionTestUtils.setField(image, "uploadId", UUID.randomUUID());
        return image;
    }

    private void stubPage(Long afterId, List<ProductImage> content) {
        when(productImageRepository.findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc(
                eq(UploadStatus.PENDING), eq(afterId), any(), any()))
                .thenReturn(content);
    }

    @Test
    void 유예_시간이_지난_PENDING_이미지의_S3_객체를_지우고_행을_지운다() {
        ProductImage image = imageFixture(10L, "products/ab/key.jpg");
        stubPage(0L, List.of(image));
        stubPage(10L, List.of());

        sut.cleanupExpiredPending();

        verify(imageStorageClient).deleteObjectOrThrow("products/ab/key.jpg");
        verify(productImageRepository).deleteByIdAndUploadStatus(10L, UploadStatus.PENDING);
    }

    @Test
    void S3_삭제가_실패하면_그_행은_지우지_않고_나머지는_계속_처리한다() {
        ProductImage failing = imageFixture(10L, "products/ab/failing.jpg");
        ProductImage succeeding = imageFixture(20L, "products/cd/succeeding.jpg");
        stubPage(0L, List.of(failing, succeeding));
        stubPage(20L, List.of());
        doThrow(SdkClientException.create("Unable to execute HTTP request"))
                .when(imageStorageClient).deleteObjectOrThrow("products/ab/failing.jpg");

        sut.cleanupExpiredPending();

        verify(productImageRepository, never()).deleteByIdAndUploadStatus(eq(10L), any());
        verify(imageStorageClient).deleteObjectOrThrow("products/cd/succeeding.jpg");
        verify(productImageRepository).deleteByIdAndUploadStatus(20L, UploadStatus.PENDING);
    }

    // (PERF-4-03과 같은 이유) 페이지 경계를 넘는 대상도 id 커서로 이어서 다음 페이지까지 처리하는지 검증한다
    @Test
    void 페이지_경계를_넘는_대상도_커서로_이어서_처리한다() {
        ProductImage f1 = imageFixture(10L, "products/ab/key1.jpg");
        ProductImage f2 = imageFixture(20L, "products/cd/key2.jpg");
        stubPage(0L, List.of(f1));
        stubPage(10L, List.of(f2));
        stubPage(20L, List.of());

        sut.cleanupExpiredPending();

        verify(imageStorageClient).deleteObjectOrThrow("products/ab/key1.jpg");
        verify(imageStorageClient).deleteObjectOrThrow("products/cd/key2.jpg");
    }

    @Test
    void 유예_시간을_기준으로_cutoff를_계산해_조회_조건으로_넘긴다() {
        stubPage(0L, List.of());

        sut.cleanupExpiredPending();

        verify(productImageRepository).findByUploadStatusAndIdGreaterThanAndCreatedAtBeforeOrderByIdAsc(
                eq(UploadStatus.PENDING), eq(0L), eq(NOW.minusHours(GRACE_HOURS)), any());
    }

    @Test
    void 대상이_없으면_아무것도_지우지_않는다() {
        stubPage(0L, List.of());

        sut.cleanupExpiredPending();

        verify(imageStorageClient, never()).deleteObjectOrThrow(any());
        verify(productImageRepository, never()).deleteByIdAndUploadStatus(anyLong(), any());
    }
}
