package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.client.S3ImageStorageClient;
import com.freshmarket.product.domain.client.S3ObjectMetadata;
import com.freshmarket.product.domain.dto.AdminProductImageConfirmRequest;
import com.freshmarket.product.domain.dto.AdminProductImageConfirmResponse;
import com.freshmarket.product.domain.dto.AdminProductImageCreateUploadUrlRequest;
import com.freshmarket.product.domain.dto.AdminProductImageUploadUrlResponse;
import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.exception.ProductErrorCode;
import com.freshmarket.product.domain.exception.ProductException;
import com.freshmarket.product.domain.repository.ProductImageRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

/*
 * ProductRepository/ProductImageRepository/S3ImageStorageClient 전부 들어오는 데이터를 제공하는
 * 의존성이라 mock이다(UT-4-01). AdminAuthServiceTest와 같은 이유로 @InjectMocks 대신 생성자를
 * 직접 호출한다 — allowedContentTypesCsv/maxSizeBytes가 @Value 원시값이라 자동 주입이 안 된다.
 */
class AdminProductImageServiceTest {

    private static final String ALLOWED_CONTENT_TYPES = "image/jpeg,image/png,image/webp";
    private static final long MAX_SIZE_BYTES = 1_048_576L;

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductImageRepository productImageRepository = mock(ProductImageRepository.class);
    private final S3ImageStorageClient s3ImageStorageClient = mock(S3ImageStorageClient.class);

    private final AdminProductImageService adminProductImageService = new AdminProductImageService(
            productRepository, productImageRepository, s3ImageStorageClient,
            ALLOWED_CONTENT_TYPES, MAX_SIZE_BYTES);

    // ---- createUploadUrl() ----

    @Test
    void 업로드_URL을_발급한다() {
        // given
        when(productRepository.existsById(1L)).thenReturn(true);
        when(s3ImageStorageClient.createPresignedPutUrl(any(), any())).thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("image/jpeg", 100_000L);

        // when
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(1L, request);

        // then
        assertThat(response.uploadUrl()).isEqualTo("https://s3.example.com/signed");
        assertThat(response.objectKey()).matches("products/[0-9a-f]{2}/[0-9a-f]{32}\\.jpg");
        verify(productImageRepository).save(any(ProductImage.class));
    }

    @Test
    void 상품이_없으면_실패한다() {
        // given
        when(productRepository.existsById(999L)).thenReturn(false);
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("image/jpeg", 100_000L);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.createUploadUrl(999L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.PRODUCT_NOT_FOUND);
        verify(productImageRepository, never()).save(any());
    }

    @Test
    void 허용되지_않는_콘텐츠_타입이면_실패한다() {
        // given
        when(productRepository.existsById(1L)).thenReturn(true);
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("application/pdf", 100_000L);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.createUploadUrl(1L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_INVALID_CONTENT_TYPE);
        verify(productImageRepository, never()).save(any());
    }

    @Test
    void 파일_크기가_상한을_넘으면_실패한다() {
        // given
        when(productRepository.existsById(1L)).thenReturn(true);
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("image/jpeg", MAX_SIZE_BYTES + 1);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.createUploadUrl(1L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_TOO_LARGE);
        verify(productImageRepository, never()).save(any());
    }

    // ---- confirm() ----

    @Test
    void 업로드를_확정한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(s3ImageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100_000L, "image/jpeg")));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when
        AdminProductImageConfirmResponse response = adminProductImageService.confirm(1L, 88L, request);

        // then
        assertThat(response.productImageId()).isEqualTo(88L);
        assertThat(image.getUploadStatus().name()).isEqualTo("CONFIRMED");
        verify(s3ImageStorageClient, never()).deleteObject(any());
    }

    @Test
    void uploadId로_못_찾으면_실패한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.empty());
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
    }

    @Test
    void 경로의_상품이나_이미지와_어긋나면_실패한다() {
        // given — uploadId는 유효하지만 그 행은 다른 상품(2L) 소속이다
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 2L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then — productId가 1L인 경로로 요청함
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
    }

    @Test
    void 이미_확정된_이미지를_다시_확정하면_실패한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        image.confirm();
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_ALREADY_CONFIRMED);
    }

    @Test
    void 업로드가_아직_확인되지_않으면_실패한다() {
        // given — HeadObject가 404(빈 값)
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(s3ImageStorageClient.headObject("products/ab/key.jpg")).thenReturn(Optional.empty());
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND);
    }

    @Test
    void 업로드된_파일이_신고한_크기를_넘으면_지우고_실패한다() {
        // given — 발급 때는 정상 크기였지만 실제로는 상한을 넘는 파일이 올라온 상황
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(s3ImageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(MAX_SIZE_BYTES + 1, "image/jpeg")));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        verify(s3ImageStorageClient).deleteObject("products/ab/key.jpg");
    }

    @Test
    void 업로드된_파일의_형식이_다르면_지우고_실패한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(s3ImageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100L, "application/pdf")));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        verify(s3ImageStorageClient).deleteObject("products/ab/key.jpg");
    }

    // ---- delete() ----

    @Test
    void 이미지를_삭제하면_S3_객체를_먼저_지우고_DB_행을_지운다() {
        // given — (INF-11-08) 순서가 반대면 행을 잃어 객체를 다시 찾을 방법이 없어진다
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", UUID.randomUUID());
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.of(image));

        // when
        adminProductImageService.delete(1L, 88L);

        // then
        InOrder order = inOrder(s3ImageStorageClient, productImageRepository);
        order.verify(s3ImageStorageClient).deleteObject("products/ab/key.jpg");
        order.verify(productImageRepository).delete(image);
    }

    @Test
    void 없는_이미지를_삭제하면_실패한다() {
        // given
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> adminProductImageService.delete(1L, 88L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_NOT_FOUND);
        verify(productImageRepository, never()).delete(any());
        verify(s3ImageStorageClient, never()).deleteObject(any());
    }

    private ProductImage imageFixture(Long id, Long productId, String objectKey, UUID uploadId) {
        ProductImage image = ProductImage.register(productId, objectKey);
        ReflectionTestUtils.setField(image, "id", id);
        ReflectionTestUtils.setField(image, "uploadId", uploadId);
        return image;
    }
}
