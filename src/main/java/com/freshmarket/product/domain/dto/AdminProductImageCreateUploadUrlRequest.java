package com.freshmarket.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/*
 * 업로드 URL 발급 요청. 클라이언트가 올릴 파일의 정보를 미리 신고한다.
 * 이 값들이 presigned PUT 서명에 실려, S3가 실제 업로드 시점에 조건이 다르면 거부한다
 * (백엔드공통_이미지저장소_설계.md 6.2절 "업로드 크기 상한").
 *
 * contentLength 상한(@Max)은 절대적인 안전선이다. 실제 업로드 허용 크기(1MB 등)는
 * 설정값(upload.product-image.max-size-bytes)이 정하고 서비스에서 검사한다 — 이 값은
 * 그보다 훨씬 크게 잡아, 말도 안 되는 입력만 여기서 걸러낸다.
 */
public record AdminProductImageCreateUploadUrlRequest(
        @Schema(description = "업로드할 파일의 MIME 타입", example = "image/jpeg") @NotBlank String contentType,
        @Schema(description = "업로드할 파일의 바이트 크기", example = "482913")
        @Positive @Max(100_000_000) long contentLength
) {
}
