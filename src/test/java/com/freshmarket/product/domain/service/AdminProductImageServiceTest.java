package com.freshmarket.product.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.domain.client.ImageStorageClient;
import com.freshmarket.product.domain.client.S3ObjectMetadata;
import com.freshmarket.product.domain.dto.AdminProductImageConfirmRequest;
import com.freshmarket.product.domain.dto.AdminProductImageConfirmResponse;
import com.freshmarket.product.domain.dto.AdminProductImageCreateUploadUrlRequest;
import com.freshmarket.product.domain.dto.AdminProductImageUploadUrlResponse;
import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.UploadStatus;
import com.freshmarket.product.domain.exception.ProductErrorCode;
import com.freshmarket.product.domain.exception.ProductException;
import com.freshmarket.product.domain.repository.ProductImageRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * ProductRepository/ProductImageRepository/ImageStorageClient 전부 들어오는 데이터를 제공하는
 * 의존성이라 mock이다(UT-4-01). AdminAuthServiceTest와 같은 이유로 @InjectMocks 대신 생성자를
 * 직접 호출한다 — allowedContentTypesCsv/maxSizeBytes가 @Value 원시값이라 자동 주입이 안 된다.
 */
class AdminProductImageServiceTest {

    private static final String ALLOWED_CONTENT_TYPES = "image/jpeg,image/png,image/webp";
    private static final long MAX_SIZE_BYTES = 1_048_576L;
    private static final long STALE_PENDING_MINUTES = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 12, 0);

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductImageRepository productImageRepository = mock(ProductImageRepository.class);
    private final ImageStorageClient imageStorageClient = mock(ImageStorageClient.class);
    private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final AdminProductImageService adminProductImageService = new AdminProductImageService(
            productRepository, productImageRepository, imageStorageClient, clock,
            ALLOWED_CONTENT_TYPES, MAX_SIZE_BYTES, STALE_PENDING_MINUTES);

    // ---- 생성자 ----

    @Test
    void 허용된_콘텐츠_타입에_대응하는_확장자가_없으면_기동_시점에_실패한다() {
        // given — 설정에만 새 타입(image/gif)을 추가하고 확장자 맵은 안 늘린 상황을 재현한다
        assertThatThrownBy(() -> new AdminProductImageService(
                productRepository, productImageRepository, imageStorageClient, clock,
                "image/jpeg,image/gif", MAX_SIZE_BYTES, STALE_PENDING_MINUTES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image/gif");
    }

    // ---- createUploadUrl() ----

    @Test
    void 업로드_URL을_발급한다() {
        // given
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of());
        when(imageStorageClient.createPresignedPutUrl(any(), any(), anyLong()))
                .thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(1L, request);

        // then — objectKey는 응답에 없다(API-2-02). 실제로 생성된 key는 저장된 엔티티로 확인한다
        assertThat(response.uploadUrl()).isEqualTo("https://s3.example.com/signed");
        ArgumentCaptor<ProductImage> imageCaptor = ArgumentCaptor.forClass(ProductImage.class);
        verify(productImageRepository).save(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getObjectKey()).matches("products/[0-9a-f]{2}/[0-9a-f]{32}\\.jpg");
    }

    @Test
    void 같은_요청_식별자로_재시도하면_기존_이미지를_그대로_반환한다() {
        // given — 이전 요청으로 이미 발급된 이미지가 있는 상황(사전 조회에서 바로 잡힘)
        ProductImage existing = imageFixture(88L, 1L, "products/ab/existing.jpg", UUID.randomUUID());
        when(productImageRepository.findByRequestIdAndProductId("req-1", 1L)).thenReturn(Optional.of(existing));
        when(imageStorageClient.createPresignedPutUrl("products/ab/existing.jpg", "image/jpeg", 100_000L))
                .thenReturn("https://s3.example.com/re-signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(1L, request);

        // then — 새로 만들지 않고 기존 이미지 그대로, presigned URL만 새로 서명해 돌려준다
        assertThat(response.productImageId()).isEqualTo(88L);
        assertThat(response.uploadUrl()).isEqualTo("https://s3.example.com/re-signed");
        verify(productImageRepository, never()).save(any());
        verify(productRepository, never()).existsById(any());
    }

    @Test
    void 상품이_없으면_실패한다() {
        // given
        when(productRepository.existsById(999L)).thenReturn(false);
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

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
                new AdminProductImageCreateUploadUrlRequest("req-1", "application/pdf", 100_000L);

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
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", MAX_SIZE_BYTES + 1);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.createUploadUrl(1L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_TOO_LARGE);
        verify(productImageRepository, never()).save(any());
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복되면_기존_이미지를_반환한다() {
        // given — 사전 조회 시점엔 없었지만, save() 직전에 동시 재시도가 먼저 커밋을 마친 경합 상황
        when(productRepository.existsById(1L)).thenReturn(true);
        ProductImage existing = imageFixture(88L, 1L, "products/ab/existing.jpg", UUID.randomUUID());
        when(productImageRepository.findByRequestIdAndProductId("req-1", 1L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of());
        when(productImageRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'product_image.uk_product_image_request_id'"));
        when(imageStorageClient.createPresignedPutUrl("products/ab/existing.jpg", "image/jpeg", 100_000L))
                .thenReturn("https://s3.example.com/re-signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(1L, request);

        // then
        assertThat(response.productImageId()).isEqualTo(88L);
    }

    @Test
    void 다른_상품에_이미_사용된_요청_식별자면_충돌_오류를_던진다() {
        // given — save() 시점에 유니크 위반이 났는데, 같은 (requestId, productId) 조합으로
        // 재조회해도 없다면 그 requestId는 다른 상품 소속이라는 뜻이다(클라이언트의 잘못된 재사용)
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productImageRepository.findByRequestIdAndProductId("req-1", 1L)).thenReturn(Optional.empty());
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of());
        when(productImageRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'product_image.uk_product_image_request_id'"));
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.createUploadUrl(1L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_REQUEST_ID_ALREADY_USED);
    }

    // ---- createUploadUrl()의 resolveStalePending() 곁다리 동작 ----

    @Test
    void URL_발급_중_같은_상품의_오래된_PENDING이_업로드돼_있으면_스스로_확정한다() {
        // given — 유예 시간(10분)을 넘긴 PENDING 행이 이 상품에 남아있는 상황
        when(productRepository.existsById(1L)).thenReturn(true);
        ProductImage stale = imageFixture(77L, 1L, "products/ab/stale.jpg", UUID.randomUUID());
        ReflectionTestUtils.setField(stale, "createdAt", NOW.minusMinutes(20));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of(stale));
        when(imageStorageClient.headObject("products/ab/stale.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100_000L, "image/jpeg")));
        when(productImageRepository.confirmIfPending(77L, UploadStatus.PENDING, UploadStatus.CONFIRMED))
                .thenReturn(1);
        when(imageStorageClient.createPresignedPutUrl(any(), any(), anyLong()))
                .thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        adminProductImageService.createUploadUrl(1L, request);

        // then — 새 발급 자체와는 무관하게, 곁다리로 오래된 행을 먼저 확정한다
        verify(productImageRepository).confirmIfPending(77L, UploadStatus.PENDING, UploadStatus.CONFIRMED);
    }

    @Test
    void URL_발급_중_유예_시간_안의_PENDING은_건드리지_않는다() {
        // given — 아직 유예 시간(10분)이 안 지난, 진행 중일 수 있는 다른 업로드
        when(productRepository.existsById(1L)).thenReturn(true);
        ProductImage recent = imageFixture(77L, 1L, "products/ab/recent.jpg", UUID.randomUUID());
        ReflectionTestUtils.setField(recent, "createdAt", NOW.minusMinutes(2));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of(recent));
        when(imageStorageClient.createPresignedPutUrl(any(), any(), anyLong()))
                .thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        adminProductImageService.createUploadUrl(1L, request);

        // then
        verify(imageStorageClient, never()).headObject(any());
    }

    @Test
    void URL_발급_중_오래된_PENDING이_아직_업로드_안됐으면_그대로_둔다() {
        // given — HeadObject 404(빈 값). 정리 배치가 유예 시간 이후 마저 처리한다
        when(productRepository.existsById(1L)).thenReturn(true);
        ProductImage stale = imageFixture(77L, 1L, "products/ab/stale.jpg", UUID.randomUUID());
        ReflectionTestUtils.setField(stale, "createdAt", NOW.minusMinutes(20));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of(stale));
        when(imageStorageClient.headObject("products/ab/stale.jpg")).thenReturn(Optional.empty());
        when(imageStorageClient.createPresignedPutUrl(any(), any(), anyLong()))
                .thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when
        adminProductImageService.createUploadUrl(1L, request);

        // then
        verify(productImageRepository, never()).confirmIfPending(any(), any(), any());
    }

    @Test
    void URL_발급_중_오래된_PENDING의_S3_조회가_실패해도_새_발급은_실패하지_않는다() {
        // given — 곁다리 작업이라, 실패해도 요청 자체를 막지 않는다
        when(productRepository.existsById(1L)).thenReturn(true);
        ProductImage stale = imageFixture(77L, 1L, "products/ab/stale.jpg", UUID.randomUUID());
        ReflectionTestUtils.setField(stale, "createdAt", NOW.minusMinutes(20));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.PENDING))
                .thenReturn(List.of(stale));
        when(imageStorageClient.headObject("products/ab/stale.jpg"))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));
        when(imageStorageClient.createPresignedPutUrl(any(), any(), anyLong()))
                .thenReturn("https://s3.example.com/signed");
        AdminProductImageCreateUploadUrlRequest request =
                new AdminProductImageCreateUploadUrlRequest("req-1", "image/jpeg", 100_000L);

        // when, then — 예외 없이 새 URL 발급까지 끝난다
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(1L, request);
        assertThat(response.uploadUrl()).isEqualTo("https://s3.example.com/signed");
    }

    // ---- confirm() ----

    @Test
    void 업로드를_확정한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100_000L, "image/jpeg")));
        when(productImageRepository.confirmIfPending(88L, UploadStatus.PENDING, UploadStatus.CONFIRMED))
                .thenReturn(1);
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when
        AdminProductImageConfirmResponse response = adminProductImageService.confirm(1L, 88L, request);

        // then — DI-4-02: 트랜잭션 안 엔티티 변경(dirty checking)이 아니라 원자적 UPDATE로 확정한다
        assertThat(response.productImageId()).isEqualTo(88L);
        verify(productImageRepository).confirmIfPending(88L, UploadStatus.PENDING, UploadStatus.CONFIRMED);
        verify(imageStorageClient, never()).deleteObject(any());
    }

    @Test
    void 확정_중_S3_조회가_실패하면_재시도_가능한_오류를_던진다() {
        // given — HeadObject가 404가 아닌 다른 이유(타임아웃, 5xx 등)로 실패한 상황
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(500).message("Internal Error").build());
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then — 업로드 미확인(404)과 구분해서 별도 오류 코드로 변환한다
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_VERIFICATION_UNAVAILABLE);
    }

    @Test
    void 확정_중_S3_연결이_실패해도_재시도_가능한_오류를_던진다() {
        // given — (FUN-2-01, FUN-2-04) 연결 실패·apiCallTimeout은 S3Exception이 아니라
        // SdkClientException(SdkException의 다른 자식)이다. S3Exception만 잡으면 이 경로가 새어나간다
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenThrow(SdkClientException.create("Unable to execute HTTP request"));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_VERIFICATION_UNAVAILABLE);
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
    void 확정_직전에_다른_요청이_먼저_확정하면_실패한다() {
        // given — DI-4-02로 락을 없앤 뒤의 경합: HeadObject 이후, 원자적 UPDATE 직전에 동시 확정
        // 요청이 먼저 커밋해 PENDING 조건이 깨진 상황(영향받은 행 0). 행은 남아 있으므로 이미
        // 확정된 것으로 본다
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100_000L, "image/jpeg")));
        when(productImageRepository.confirmIfPending(88L, UploadStatus.PENDING, UploadStatus.CONFIRMED))
                .thenReturn(0);
        when(productImageRepository.existsById(88L)).thenReturn(true);
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_ALREADY_CONFIRMED);
    }

    @Test
    void 확정_직전에_다른_요청이_먼저_삭제하면_실패한다() {
        // given — 위와 같은 경합이지만, 그 사이 delete()가 먼저 행을 지운 경우(영향받은 행 0,
        // 재조회해도 행이 없음)
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100_000L, "image/jpeg")));
        when(productImageRepository.confirmIfPending(88L, UploadStatus.PENDING, UploadStatus.CONFIRMED))
                .thenReturn(0);
        when(productImageRepository.existsById(88L)).thenReturn(false);
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
        when(imageStorageClient.headObject("products/ab/key.jpg")).thenReturn(Optional.empty());
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
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(MAX_SIZE_BYTES + 1, "image/jpeg")));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        verify(imageStorageClient).deleteObject("products/ab/key.jpg");
    }

    @Test
    void 업로드된_파일의_형식이_다르면_지우고_실패한다() {
        // given
        UUID uploadId = UUID.randomUUID();
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", uploadId);
        when(productImageRepository.findByUploadId(uploadId)).thenReturn(Optional.of(image));
        when(imageStorageClient.headObject("products/ab/key.jpg"))
                .thenReturn(Optional.of(new S3ObjectMetadata(100L, "application/pdf")));
        AdminProductImageConfirmRequest request = new AdminProductImageConfirmRequest(uploadId);

        // when, then
        assertThatThrownBy(() -> adminProductImageService.confirm(1L, 88L, request))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        verify(imageStorageClient).deleteObject("products/ab/key.jpg");
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
        InOrder order = inOrder(imageStorageClient, productImageRepository);
        order.verify(imageStorageClient).deleteObjectOrThrow("products/ab/key.jpg");
        order.verify(productImageRepository).deleteByIdAndProductId(88L, 1L);
    }

    @Test
    void S3_객체_삭제가_실패하면_DB_행을_지우지_않는다() {
        // given — (INF-11-08) 객체 삭제 실패가 삼켜지면 행만 지워져 객체가 고아로 남는다
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", UUID.randomUUID());
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.of(image));
        S3Exception s3Exception = (S3Exception) S3Exception.builder().statusCode(500).build();
        doThrow(s3Exception).when(imageStorageClient).deleteObjectOrThrow("products/ab/key.jpg");

        // when, then
        assertThatThrownBy(() -> adminProductImageService.delete(1L, 88L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_DELETE_FAILED)
                .hasCause(s3Exception);
        verify(productImageRepository, never()).deleteByIdAndProductId(any(), any());
    }

    @Test
    void S3_연결이_실패해도_DB_행을_지우지_않는다() {
        // given — (FUN-2-01, FUN-2-04) S3Exception이 아닌 SdkClientException(연결 실패 등)도
        // 같은 방식으로 재시도 가능한 오류로 변환되어야 한다
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", UUID.randomUUID());
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.of(image));
        SdkClientException sdkClientException = SdkClientException.create("Unable to execute HTTP request");
        doThrow(sdkClientException).when(imageStorageClient).deleteObjectOrThrow("products/ab/key.jpg");

        // when, then
        assertThatThrownBy(() -> adminProductImageService.delete(1L, 88L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.IMAGE_DELETE_FAILED)
                .hasCause(sdkClientException);
        verify(productImageRepository, never()).deleteByIdAndProductId(any(), any());
    }

    @Test
    void 삭제_직전에_다른_요청이_먼저_지웠어도_성공한다() {
        // given — DI-4-02로 락을 없앤 뒤의 경합: S3 삭제(멱등) 이후, DB 삭제 직전에 동시 삭제
        // 요청이 먼저 행을 지운 상황(영향받은 행 0). 목표 상태(행 없음)에 이미 도달했으므로 오류가
        // 아니다
        ProductImage image = imageFixture(88L, 1L, "products/ab/key.jpg", UUID.randomUUID());
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.of(image));
        when(productImageRepository.deleteByIdAndProductId(88L, 1L)).thenReturn(0);

        // when, then — 예외 없이 끝난다
        adminProductImageService.delete(1L, 88L);
        verify(imageStorageClient).deleteObjectOrThrow("products/ab/key.jpg");
    }

    @Test
    void 없는_이미지를_삭제해도_성공한다() {
        // given — (API-3-07) 이미 지워졌거나 존재한 적 없는 imageId. 목표 상태(행 없음)에 이미
        // 도달했으므로 오류가 아니다
        when(productImageRepository.findByIdAndProductId(88L, 1L)).thenReturn(Optional.empty());

        // when, then — 예외 없이 끝난다
        adminProductImageService.delete(1L, 88L);
        verify(productImageRepository, never()).deleteByIdAndProductId(any(), any());
        verify(imageStorageClient, never()).deleteObjectOrThrow(any());
    }

    private ProductImage imageFixture(Long id, Long productId, String objectKey, UUID uploadId) {
        ProductImage image = ProductImage.register(productId, "req-fixture-" + id, objectKey);
        ReflectionTestUtils.setField(image, "id", id);
        ReflectionTestUtils.setField(image, "uploadId", uploadId);
        return image;
    }
}
