package com.freshmarket.product.internal.controller;

import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.product.internal.dto.CategoryResponse;
import com.freshmarket.product.internal.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 회원에게 카테고리 목록을 노출한다
@RestController
@RequiredArgsConstructor
class CategoryController {

    private final CategoryService categoryService;

    @Operation(summary = "카테고리 목록 조회", description = "현재는 최상위 카테고리만 반환한다.")
    /*
     * 카테고리는 트리 구조라 화면에서 항상 전체를 구성해야 한다. 페이지네이션을 넣지 않는다.
     * (AdminCategoryController 와 같은 사유. API-5-01/API-3-04/FUN-3-03 의 의도적 예외)
     * 최상위 5종 규모라 건수 상한도 두지 않는다. 하위 카테고리가 늘어나면 재검토한다.
     */
    @GetMapping("/v1/categories")
    public ResponseEntity<ResponseEnvelope<List<CategoryResponse>>> getCategories() {
        return ResponseEntity.ok(ResponseEnvelope.success(categoryService.getCategories()));
    }
}