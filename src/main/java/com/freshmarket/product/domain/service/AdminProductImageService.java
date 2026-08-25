package com.freshmarket.product.domain.service;

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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 화면에서 상품 이미지를 업로드·확정·삭제하는 기능을 담당한다 (#21).
 * 업로드는 앱을 거치지 않고 클라이언트가 S3로 직접 올린다 — 서버는 URL 발급과 확정만 한다
 * (백엔드공통_이미지저장소_설계.md 6.2절).
 */
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
    }

    /*
     * 업로드 URL을 발급한다. 이 시점엔 실제로 S3에 올라갔는지 모른다 — 행을 PENDING으로 먼저
     * 만들어 두고 그 key로만 서명한다. 키를 클라이언트에게 받지 않는 이유는 남의 키를 실어 보내
     * 남의 이미지를 자기 상품에 붙이는 경로를 원천 차단하기 위해서다(schema-design-rationale.md 7장).
     */
    @Transactional
    public AdminProductImageUploadUrlResponse createUploadUrl(Long productId,
            AdminProductImageCreateUploadUrlRequest request) {
        if (!productRepository.existsById(productId)) {
            throw new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        validateContentType(request.contentType());
        validateContentLength(request.contentLength());

        String objectKey = generateObjectKey(request.contentType());
        ProductImage image = ProductImage.register(productId, objectKey);
        productImageRepository.save(image);

        String uploadUrl = s3ImageStorageClient.createPresignedPutUrl(objectKey, request.contentType());
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
     */
    @Transactional
    public AdminProductImageConfirmResponse confirm(Long productId, Long imageId,
            AdminProductImageConfirmRequest request) {
        ProductImage image = productImageRepository.findByUploadId(request.uploadId())
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
     */
    @Transactional
    public void delete(Long productId, Long imageId) {
        ProductImage image = productImageRepository.findByIdAndProductId(imageId, productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.IMAGE_NOT_FOUND));
        s3ImageStorageClient.deleteObject(image.getObjectKey());
        productImageRepository.delete(image);
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
