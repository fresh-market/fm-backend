package com.freshmarket.product.internal.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.internal.dto.AdminCategoryCreateRequest;
import com.freshmarket.product.internal.dto.AdminCategoryUpdateRequest;
import com.freshmarket.product.internal.dto.CategoryResponse;
import com.freshmarket.product.internal.service.AdminCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 관리자용 카테고리 목록/단건 조회, 등록, 이름 변경, 삭제 API
@RestController
@RequestMapping("/v1/admin/categories")
class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    AdminCategoryController(AdminCategoryService adminCategoryService) {
        this.adminCategoryService = adminCategoryService;
    }

    // 카테고리는 트리 구조라 화면에서 항상 전체를 구성해야 한다. 페이지네이션을 넣지 않는다
    @Operation(summary = "카테고리 전체 조회", description = "모든 카테고리를 트리 구성에 필요한 형태로 한 번에 반환한다.")
    @GetMapping
    public ResponseEntity<ResponseEnvelope<List<CategoryResponse>>> findAll() {
        return ResponseEntity.ok(ResponseEnvelope.success(adminCategoryService.findAll()));
    }

    @Operation(summary = "카테고리 단건 조회")
    @GetMapping("/{categoryId}")
    public ResponseEntity<ResponseEnvelope<CategoryResponse>> findById(@PathVariable Long categoryId) {
        return ResponseEntity.ok(ResponseEnvelope.success(adminCategoryService.findById(categoryId)));
    }

    @Operation(summary = "카테고리 등록", description = "parentId를 생략하면 최상위 카테고리로 등록한다.")
    @PostMapping
    public ResponseEntity<ResponseEnvelope<CategoryResponse>> register(
            @Valid @RequestBody AdminCategoryCreateRequest request) {
        CategoryResponse response = adminCategoryService.register(request.name(), request.parentId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @Operation(summary = "카테고리 이름 변경")
    @PatchMapping("/{categoryId}")
    public ResponseEntity<ResponseEnvelope<CategoryResponse>> rename(
            @PathVariable Long categoryId, @Valid @RequestBody AdminCategoryUpdateRequest request) {
        CategoryResponse response = adminCategoryService.rename(categoryId, request.name());
        return ResponseEntity.ok(ResponseEnvelope.success(response));
    }

    @Operation(
            summary = "카테고리 삭제",
            description = "하위 카테고리가 있거나 소속 상품이 있으면 409로 거부한다.")
    @DeleteMapping("/{categoryId}")
    public ResponseEntity<ResponseEnvelope<Void>> delete(@PathVariable Long categoryId) {
        adminCategoryService.delete(categoryId);
        return ResponseEntity.ok(ResponseEnvelope.success());
    }
}
