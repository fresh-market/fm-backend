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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.model.S3Exception;

/*
 * 관리자 화면에서 상품 이미지를 업로드·확정·삭제하는 기능을 담당한다 (#21).
 * 업로드는 앱을 거치지 않고 클라이언트가 S3로 직접 올린다 — 서버는 URL 발급과 확정만 한다
 * (백엔드공통_이미지저장소_설계.md 6.2절).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
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
     * 조회에 쓰기 락을 건다(findByUploadIdForUpdate) — 같은 이미지를 동시에 건드리는
     * confirm()/delete()끼리 경합하면(같은 uploadId 중복 확정 요청, 확정 중 삭제 등) 조회~커밋
     * 사이 상태가 바뀔 창을 없앤다(AdminLotService.findLotForUpdate와 같은 이유).
     */
    @Transactional
    public AdminProductImageConfirmResponse confirm(Long productId, Long imageId,
            AdminProductImageConfirmRequest request) {
        ProductImage image = findByUploadIdForUpdate(request.uploadId())
                .filter(found -> found.getId().equals(imageId) && found.getProductId().equals(productId))
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_NOT_FOUND));

        if (image.getUploadStatus() != UploadStatus.PENDING) {
            throw new ProductException(ProductErrorCode.IMAGE_ALREADY_CONFIRMED);
        }

        S3ObjectMetadata metadata = s3ImageStorageClient.headObject(image.getObjectKey())
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_UPLOAD_NOT_FOUND));

        // 발급 시 신고한 조건과 실제 업로드가 다르면(서명되지 않은 조건이라 S3가 막지 못한다) 지우고 거절한다
        if (metadata.contentLength() > maxSizeBytes || !allowedContentTypes.contains(metadata.contentType())) {
            s3ImageStorageClient.deleteObject(image.getObjectKey());
            throw new ProductException(ProductErrorCode.IMAGE_UPLOAD_MISMATCH);
        }

        image.confirm();
        return AdminProductImageConfirmResponse.of(image);
    }

    /*
     * 이미지를 삭제한다. S3 객체를 먼저 지우고 그다음 DB 행을 지운다(INF-11-08).
     * 순서가 반대면 행을 잃어 그 객체를 다시 찾을 방법이 없어져 고아가 확정된다 — 객체를 먼저
     * 지우면 뒤이은 행 삭제가 실패해도 사용자가 다시 지워서 해소된다. DeleteObject는 없는 객체에도
     * 성공하므로(멱등) 이 순서에서 재시도가 안전하다.
     *
     * 조회에 쓰기 락을 건다 — 삭제 중인 이미지를 동시에 confirm()하는 경합을 막는다. 락 없이
     * confirm()이 먼저 커밋해 버리면, 뒤이은 delete()가 방금 확정된 이미지를 그대로 지워
     * "확정 직후 삭제"가 사용자 의도와 다르게 조용히 일어날 수 있다.
     */
    @Transactional
    public void delete(Long productId, Long imageId) {
        ProductImage image = findByIdAndProductIdForUpdate(imageId, productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_NOT_FOUND));
        try {
            s3ImageStorageClient.deleteObjectOrThrow(image.getObjectKey());
        } catch (S3Exception e) {
            // (INF-11-08) 객체 삭제가 실패하면 행을 지우지 않는다 — 그래야 다시 지워서 해소할 수 있다
            log.error("event=IMAGE_DELETE_OBJECT_FAILED productId={} imageId={} objectKey={}",
                    productId, imageId, image.getObjectKey(), e);
            throw new ProductException(ProductErrorCode.IMAGE_DELETE_FAILED, e);
        }
        productImageRepository.delete(image);
    }

    /*
     * 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로
     * 감싼다(AdminLotService.findLotForUpdate와 같은 패턴).
     */
    private Optional<ProductImage> findByUploadIdForUpdate(UUID uploadId) {
        try {
            return productImageRepository.findByUploadIdForUpdate(uploadId);
        } catch (PessimisticLockingFailureException e) {
            throw new ProductException(ProductErrorCode.IMAGE_PROCESSING_IN_PROGRESS, e);
        }
    }

    private Optional<ProductImage> findByIdAndProductIdForUpdate(Long imageId, Long productId) {
        try {
            return productImageRepository.findByIdAndProductIdForUpdate(imageId, productId);
        } catch (PessimisticLockingFailureException e) {
            throw new ProductException(ProductErrorCode.IMAGE_PROCESSING_IN_PROGRESS, e);
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
