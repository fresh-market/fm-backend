package com.freshmarket.product.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// product 도메인 전체(카테고리, 상품 등)가 함께 쓰는 오류 코드 모음
@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    CATEGORY_HAS_PRODUCTS(HttpStatus.CONFLICT, "CATEGORY-001", "소속된 상품이 있어 삭제할 수 없습니다."),
    CATEGORY_DUPLICATE_NAME(HttpStatus.CONFLICT, "CATEGORY-002", "이미 같은 이름의 카테고리가 있습니다."),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "CATEGORY-003", "카테고리를 찾을 수 없습니다."),
    CATEGORY_HAS_CHILDREN(HttpStatus.CONFLICT, "CATEGORY-004", "하위 카테고리가 있어 삭제할 수 없습니다."),
    SUPPLIER_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-005", "공급처를 찾을 수 없습니다."),
    OPTION_DUPLICATE_NAME(HttpStatus.CONFLICT, "PRODUCT-006", "이미 같은 이름의 옵션이 있습니다."),
    REGISTRATION_IN_PROGRESS(HttpStatus.CONFLICT, "PRODUCT-007", "동일한 요청이 아직 처리 중입니다. 잠시 후 다시 시도해주세요."),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-001",
            "상품을 찾을 수 없습니다. 상품 목록에서 다시 확인해 주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}