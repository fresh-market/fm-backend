package com.freshmarket.product.internal.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.internal.dto.AdminProductImageConfirmRequest;
import com.freshmarket.product.internal.dto.AdminProductImageConfirmResponse;
import com.freshmarket.product.internal.dto.AdminProductImageCreateUploadUrlRequest;
import com.freshmarket.product.internal.dto.AdminProductImageUploadUrlResponse;
import com.freshmarket.product.internal.service.AdminProductImageService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 상품 이미지 업로드 URL 발급, 확정, 삭제 API (#21)
@RestController
class AdminProductImageController {

    private final AdminProductImageService adminProductImageService;

    AdminProductImageController(AdminProductImageService adminProductImageService) {
        this.adminProductImageService = adminProductImageService;
    }

    @Operation(summary = "이미지 업로드 URL 발급",
            description = "S3에 직접 올릴 presigned URL을 발급한다. 이 시점엔 실제 업로드 여부를 모른다(PENDING).")
    @PostMapping("/v1/admin/products/{productId}/images:createUploadUrl")
    public ResponseEntity<ResponseEnvelope<AdminProductImageUploadUrlResponse>> createUploadUrl(
            @PathVariable Long productId, @Valid @RequestBody AdminProductImageCreateUploadUrlRequest request) {
        AdminProductImageUploadUrlResponse response = adminProductImageService.createUploadUrl(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "이미지 업로드 확정",
            description = "S3에 실제로 올라갔는지 HeadObject로 확인한 뒤 CONFIRMED로 바꾼다.")
    @PostMapping("/v1/admin/products/{productId}/images/{imageId}:confirm")
    public ResponseEntity<ResponseEnvelope<AdminProductImageConfirmResponse>> confirm(
            @PathVariable Long productId, @PathVariable Long imageId,
            @Valid @RequestBody AdminProductImageConfirmRequest request) {
        AdminProductImageConfirmResponse response = adminProductImageService.confirm(productId, imageId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "이미지 삭제", description = "S3 객체를 먼저 지우고 DB 행을 지운다(INF-11-08).")
    @DeleteMapping("/v1/admin/products/{productId}/images/{imageId}")
    public ResponseEntity<Void> delete(@PathVariable Long productId, @PathVariable Long imageId) {
        adminProductImageService.delete(productId, imageId);
        return ResponseEntity.noContent().build();
    }
}
