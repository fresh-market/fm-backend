package com.freshmarket.product.domain.service;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

import com.freshmarket.product.domain.client.S3ImageStorageClient;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * 관리자 화면에서 상품 이미지를 업로드·확정·삭제하는 기능을 담당한다 (#21).
 * 업로드는 앱을 거치지 않고 클라이언트가 S3로 직접 올린다 — 서버는 URL 발급과 확정만 한다
 * (백엔드공통_이미지저장소_설계.md 6.2절).
 */
/*
 * (DI-4-02) 클래스 레벨 @Transactional(readOnly = true)을 두지 않는다 — confirm()/delete()가
 * S3 호출을 트랜잭션 밖에서 실행해야 하는데, 클래스 기본값이 있으면 메서드 전체가 그 트랜잭션에
 * 묶여 버린다. 대신 각 메서드가 필요한 만큼만 Spring Data 리포지토리 메서드(자체적으로 트랜잭션을
 * 가진다)로 짧게 끊어 처리한다 — MemberLoginService.login()과 같은 구조다.
 */
@Slf4j
@Service
public class AdminProductImageService {

    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    // 한 프리픽스에 객체가 몰리지 않게 나누는 접두어 길이
    private static final int KEY_PREFIX_LENGTH = 2;

    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final S3ImageStorageClient s3ImageStorageClient;
    private final Set<String> allowedContentTypes;
    private final long maxSizeBytes;

    public AdminProductImageService(ProductRepository productRepository,
            ProductImageRepository productImageRepository, S3ImageStorageClient s3ImageStorageClient,
            @Value("${upload.product-image.allowed-content-types}") String allowedContentTypesCsv,
            @Value("${upload.product-image.max-size-bytes}") long maxSizeBytes) {
        this.productRepository = productRepository;
        this.productImageRepository = productImageRepository;
        this.s3ImageStorageClient = s3ImageStorageClient;
        this.allowedContentTypes = Set.of(allowedContentTypesCsv.split(","));
        this.maxSizeBytes = maxSizeBytes;
        validateExtensionMappingCoversConfig();
    }

    /*
     * allowedContentTypes(설정값)와 EXTENSION_BY_CONTENT_TYPE(코드 상수)는 서로 다른 소스라
     * 어긋날 수 있다 — 설정에만 새 타입을 추가하면 발급 검증은 통과하고 나서
     * generateObjectKey()에서야 터진다. 기동 시점에 미리 검증해 그 상황을 막는다.
     */
    private void validateExtensionMappingCoversConfig() {
        for (String contentType : allowedContentTypes) {
            if (!EXTENSION_BY_CONTENT_TYPE.containsKey(contentType)) {
                throw new IllegalStateException(
                        "허용된 콘텐츠 타입에 대응하는 확장자가 없다: " + contentType
                                + " (EXTENSION_BY_CONTENT_TYPE에 추가해야 한다)");
            }
        }
    }

    /*
     * 업로드 URL을 발급한다. 이 시점엔 실제로 S3에 올라갔는지 모른다 — 행을 PENDING으로 먼저
     * 만들어 두고 그 key로만 서명한다. 키를 클라이언트에게 받지 않는 이유는 남의 키를 실어 보내
     * 남의 이미지를 자기 상품에 붙이는 경로를 원천 차단하기 위해서다(schema-design-rationale.md 7장).
     *
     * 같은 requestId로 재시도가 오면(API-5-07, AIP-155) 새로 발급하지 않고 최초 결과를 그대로
     * 돌려준다 — register()/dispose()와 같은 이유·같은 이중 방어 구조다(사전 조회 + save() 시점
     * 유니크 위반). presigned URL은 매번 새로 서명해서 돌려준다 — URL 자체는 저장하지 않고
     * (INF-11-05) 만료 시간이 있어(TTL), 재시도 응답이라도 그 자리에서 바로 쓸 수 있어야 한다.
     */
    @Transactional
    public AdminProductImageUploadUrlResponse createUploadUrl(Long productId,
            AdminProductImageCreateUploadUrlRequest request) {
        Optional<ProductImage> existingImage = productImageRepository.findByRequestIdAndProductId(
                request.requestId(), productId);
        if (existingImage.isPresent()) {
            return responseOf(existingImage.get(), request.contentType(), request.contentLength());
        }

        if (!productRepository.existsById(productId)) {
            throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        validateContentType(request.contentType());
        validateContentLength(request.contentLength());

        String objectKey = generateObjectKey(request.contentType());
        ProductImage image = ProductImage.register(productId, request.requestId(), objectKey);
        try {
            productImageRepository.save(image);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_product_image_request_id")) {
                return productImageRepository.findByRequestIdAndProductId(request.requestId(), productId)
                        .map(found -> responseOf(found, request.contentType(), request.contentLength()))
                        .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_REQUEST_ID_ALREADY_USED));
            }
            throw e;
        }

        return responseOf(image, request.contentType(), request.contentLength());
    }

    private AdminProductImageUploadUrlResponse responseOf(ProductImage image, String contentType,
            long contentLength) {
        String uploadUrl = s3ImageStorageClient.createPresignedPutUrl(image.getObjectKey(), contentType,
                contentLength);
        return AdminProductImageUploadUrlResponse.of(image, uploadUrl);
    }

    /*
     * 업로드 완료를 확정한다. uploadId로 행을 찾는다(productImageId가 아니다) — 완료 통지 요청이
     * 이 업로드를 발급받은 본인인지 확인하는 유일한 근거이기 때문이다(ProductImage.uploadId 주석 참고).
     * 경로의 productId/imageId와 어긋나면 남의 발급 건을 실어 보낸 것이라 404로 통일한다 — 둘을
     * 구분해 주면 번호를 넣어 보며 남의 발급 건이 있는지 훑을 수 있게 된다.
     *
     * 확정 근거는 통지가 아니라 HeadObject다(INF-11-10) — 통지만으로 확정하면 PUT을 안 했거나
     * 실패했어도 확정되어 객체 없는 key가 조회에 나갈 수 있다.
     *
     * (DI-4-02) S3 호출(HeadObject, 조건 불일치 시 DeleteObject)은 트랜잭션 밖에서 끝낸다. DB
     * 상태 전이는 confirmOrThrow()의 원자적 UPDATE 하나로만 하고, 조회에는 더는 쓰기 락을 걸지
     * 않는다 — "PENDING일 때만" 조건으로 갱신해 그 사이 값이 바뀌었으면(중복 확정, 확정 중 삭제)
     * 영향받은 행이 0이 되어 실패로 걸러진다(MemberLoginService.login()과 같은 이유).
     */
    public AdminProductImageConfirmResponse confirm(Long productId, Long imageId,
            AdminProductImageConfirmRequest request) {
        ProductImage image = findConfirmableImage(productId, imageId, request.uploadId());

        S3ObjectMetadata metadata = headObject(image.getObjectKey())
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND));

        // 발급 시 신고한 조건과 실제 업로드가 다르면(서명되지 않은 조건이라 S3가 막지 못한다) 지우고 거절한다
        if (metadata.contentLength() > maxSizeBytes || !allowedContentTypes.contains(metadata.contentType())) {
            s3ImageStorageClient.deleteObject(image.getObjectKey());
            throw new ProductException(ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        }

        confirmOrThrow(image.getId());
        return AdminProductImageConfirmResponse.of(image);
    }

    private ProductImage findConfirmableImage(Long productId, Long imageId, UUID uploadId) {
        ProductImage image = productImageRepository.findByUploadId(uploadId)
                .filter(found -> found.getId().equals(imageId) && found.getProductId().equals(productId))
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_NOT_FOUND));
        if (image.getUploadStatus() != UploadStatus.PENDING) {
            throw new ProductException(ProductErrorCode.IMAGE_ALREADY_CONFIRMED);
        }
        return image;
    }

    /*
     * 여기서 영향받은 행이 0이면, S3 확인 이후 이 행이 바뀐 것이다(동시 확정 요청, 확정 중 삭제).
     * 어느 쪽인지 구분하려고 한 번 더 조회한다 — 행이 남아 있으면 이미 확정된 것이고, 없으면
     * 그 사이 삭제된 것이다.
     */
    private void confirmOrThrow(Long imageId) {
        int updated = productImageRepository.confirmIfPending(imageId, UploadStatus.PENDING, UploadStatus.CONFIRMED);
        if (updated == 0) {
            boolean stillExists = productImageRepository.existsById(imageId);
            throw new ProductException(
                    stillExists ? ProductErrorCode.IMAGE_ALREADY_CONFIRMED : ProductErrorCode.IMAGE_NOT_FOUND);
        }
    }

    /*
     * 이미지를 삭제한다. S3 객체를 먼저 지우고 그다음 DB 행을 지운다(INF-11-08).
     * 순서가 반대면 행을 잃어 그 객체를 다시 찾을 방법이 없어져 고아가 확정된다 — 객체를 먼저
     * 지우면 뒤이은 행 삭제가 실패해도 사용자가 다시 지워서 해소된다. DeleteObject는 없는 객체에도
     * 성공하므로(멱등) 이 순서에서 재시도가 안전하다.
     *
     * (DI-4-02) S3 삭제는 트랜잭션 밖에서 끝낸다. DB 행 삭제는 deleteByIdAndProductId()의 원자적
     * DELETE 하나로만 하고, 조회에는 더는 쓰기 락을 걸지 않는다 — DeleteObject·DELETE 둘 다 멱등이라
     * 그 사이 다른 요청이 먼저 지웠어도(영향받은 행 0) 목표 상태(행 없음)에 이미 도달한 것이라
     * 오류로 보지 않는다. 이 락은 원래 confirm() 직후 delete()가 곧바로 도는 순서를 막는
     * 용도였는데, 그 순서 자체가 데이터 정합성을 해치지 않아 트랜잭션 경계와 맞바꿀 만하다고
     * 판단했다 — 그 순서가 사용자 관점에서 문제가 된다면 별도로 다뤄야 한다.
     */
    public void delete(Long productId, Long imageId) {
        ProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_NOT_FOUND));
        try {
            s3ImageStorageClient.deleteObjectOrThrow(image.getObjectKey());
        } catch (S3Exception e) {
            // (INF-11-08) 객체 삭제가 실패하면 행을 지우지 않는다 — 그래야 다시 지워서 해소할 수 있다
            log.error("event=IMAGE_DELETE_OBJECT_FAILED productId={} imageId={} objectKey={}",
                    productId, imageId, image.getObjectKey(), e);
            throw new ProductException(ProductErrorCode.IMAGE_DELETE_FAILED, e);
        }
        productImageRepository.deleteByIdAndProductId(imageId, productId);
    }

    /*
     * (EJ-9-05/FUN-2-04) HeadObject가 404가 아닌 이유(타임아웃, 5xx 등)로 실패하면, 그건 "업로드
     * 안 됨"이 아니라 결과를 모르는 상태다 — 그대로 두면 원인 불명의 S3Exception이 서비스 경계를
     * 넘어가 GlobalExceptionHandler의 catch-all(500)로 떨어진다. 재시도 가능한 오류로 변환하고,
     * statusCode는 구조화 로그 필드로 남겨 원문 그대로 확인할 수 있게 한다(OBS-7-02).
     */
    private Optional<S3ObjectMetadata> headObject(String objectKey) {
        try {
            return s3ImageStorageClient.headObject(objectKey);
        } catch (S3Exception e) {
            log.error("event=IMAGE_HEAD_OBJECT_FAILED objectKey={} statusCode={}", objectKey, e.statusCode(), e);
            throw new ProductException(ProductErrorCode.IMAGE_VERIFICATION_UNAVAILABLE, e);
        }
    }

    private void validateContentType(String contentType) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new ProductException(ProductErrorCode.IMAGE_INVALID_CONTENT_TYPE);
        }
    }

    private void validateContentLength(long contentLength) {
        if (contentLength > maxSizeBytes) {
            throw new ProductException(ProductErrorCode.IMAGE_TOO_LARGE);
        }
    }

    // products/{2자}/{uuid}.{ext} 형식이다. 클라이언트가 준 파일명을 쓰지 않는다(경로 조작·덮어쓰기 방지, INF-11-03)
    private String generateObjectKey(String contentType) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalStateException("확장자를 알 수 없는 콘텐츠 타입이다: " + contentType);
        }
        String random = UUID.randomUUID().toString().replace("-", "");
        String prefix = random.substring(0, KEY_PREFIX_LENGTH);
        return "products/" + prefix + "/" + random + "." + extension;
    }
}
