package com.freshmarket.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/*
 * 상품 상세 응답에 딸려 나가는 이미지 하나. CONFIRMED 상태만 나온다.
 * 이미지 업로드(#21) 쪽 응답 DTO 와 용도가 달라 이름을 분리했다.
 *
 * url 은 지금 null 이다. ProductImage 는 object_key(S3 키)만 갖고 있고,
 * CDN(CloudFront) base URL 을 붙이는 설정이 아직 없다. 그 설정이 들어오면
 * ProductService 에서 base URL + objectKey 로 채운다.
 */
public record ProductDetailImageResponse(
        @Schema(description = "이미지 ID", example = "88") Long productImageId,
        @Schema(description = "이미지 URL. CDN 설정 전이라 현재는 항상 null이다") String url,
        @Schema(description = "대표 이미지 여부", example = "true") boolean isMain,
        @Schema(description = "표시 순서. 작을수록 앞", example = "0") int sortOrder
) {
}