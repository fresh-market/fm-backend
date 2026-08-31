package com.freshmarket.product.internal.dto;

import com.freshmarket.product.internal.entity.ProductImage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/*
 * 업로드 URL 발급 응답. uploadUrl은 S3 presigned URL이라 DB에 저장하지 않고 이 응답에만 실어 보낸다.
 * objectKey는 응답에 따로 넣지 않는다(백엔드공통_이미지저장소_설계.md 6.2절) — key를 돌려받아
 * 저장하면 남의 key를 실어 보내 남의 이미지를 자기 상품에 붙일 수 있다. key는 uploadUrl 경로에만 있다.
 */
public record AdminProductImageUploadUrlResponse(
        @Schema(description = "이미지 ID", example = "88") Long productImageId,
        @Schema(description = "업로드 세션 식별자. 완료 통지 요청에 그대로 실어 보낸다") UUID uploadId,
        @Schema(description = "S3 업로드 URL. 클라이언트가 이 URL로 직접 PUT한다") String uploadUrl
) {

    // ProductImage(저장된 값)와 uploadUrl(그 자리에서만 만들어지는 값, 저장 안 함)을 합쳐 응답을 만든다
    public static AdminProductImageUploadUrlResponse of(ProductImage image, String uploadUrl) {
        return new AdminProductImageUploadUrlResponse(image.getId(), image.getUploadId(), uploadUrl);
    }
}
