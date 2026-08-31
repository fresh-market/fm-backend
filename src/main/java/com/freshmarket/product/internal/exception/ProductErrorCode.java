package com.freshmarket.product.internal.exception;

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
    IMAGE_TOO_LARGE(HttpStatus.UNPROCESSABLE_CONTENT, "PRODUCT-013", "파일 크기가 상한을 넘었습니다."),
    /*
     * (INF-11-08) 객체를 먼저 지우고 행을 지우는 순서를 지키려면, 객체 삭제 실패가 호출부에
     * 전달되어 행 삭제를 막아야 한다 — 그래야 고아 객체 대신 재시도 가능한 상태로 남는다.
     */
    IMAGE_DELETE_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT-014", "이미지 삭제에 실패했습니다. 잠시 후 다시 시도해주세요."),
    /*
     * (API-5-07) requestId는 DB 전역에서 유일하다. save() 시점에 유니크 위반이 났는데 같은
     * (requestId, productId) 조합으로 재조회해도 없다면, 그 requestId는 다른 상품 소속이라는
     * 뜻이다 — 클라이언트가 같은 requestId를 서로 다른 상품에 재사용한 것(StockErrorCode.
     * REQUEST_ID_ALREADY_USED와 같은 상황).
     */
    IMAGE_REQUEST_ID_ALREADY_USED(HttpStatus.CONFLICT, "PRODUCT-016", "이미 다른 상품에 사용된 요청 식별자입니다."),
    /*
     * (EJ-9-05/FUN-2-04) HeadObject가 404가 아닌 다른 이유(타임아웃, 5xx 등)로 실패하면 "업로드가
     * 안 됐다"와 결과를 알 수 없는 상태를 구분해야 한다 — 확정하지 않되 실패로 단정하지도 않는다.
     */
    IMAGE_VERIFICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT-017",
            "이미지 업로드 확인에 실패했습니다. 잠시 후 다시 시도해주세요.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}