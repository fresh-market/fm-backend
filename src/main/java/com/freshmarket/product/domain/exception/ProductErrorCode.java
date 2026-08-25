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
            "상품을 찾을 수 없습니다. 상품 목록에서 다시 확인해 주세요."),
    /*
     * 이미지 업로드(#21). uploadId/imageId로 찾지 못하거나 경로의 productId 소속이 아니면 여기로
     * 묶는다 — 남의 발급 건인지 없는 건인지 구분해 주지 않는다(백엔드공통_이미지저장소_설계.md 6.2절).
     */
    IMAGE_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-008", "이미지를 찾을 수 없습니다."),
    IMAGE_ALREADY_CONFIRMED(HttpStatus.CONFLICT, "PRODUCT-009", "이미 확정된 이미지입니다."),
    // HeadObject가 404. 아직 PUT을 안 했거나 실패한 경우라 PENDING을 유지하고 재시도를 안내한다
    IMAGE_UPLOAD_NOT_FOUND(HttpStatus.CONFLICT, "PRODUCT-010", "업로드가 아직 확인되지 않았습니다. 잠시 후 다시 시도해주세요."),
    // HeadObject는 있는데 크기/Content-Type이 발급 시 신고한 값과 다르다
    IMAGE_UPLOAD_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT-011", "업로드된 파일이 신고한 조건과 다릅니다."),
    IMAGE_INVALID_CONTENT_TYPE(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT-012", "허용되지 않는 파일 형식입니다."),
    IMAGE_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT-013", "파일 크기가 상한을 넘었습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}