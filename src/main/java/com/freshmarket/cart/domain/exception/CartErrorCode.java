package com.freshmarket.cart.domain.exception;

import com.freshmarket.common.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum CartErrorCode implements ErrorCode {

    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-001", "장바구니를 찾을 수 없습니다."),
    CART_ITEM_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-002", "장바구니 상품을 찾을 수 없습니다."),
    PRODUCT_OPTION_NOT_PURCHASABLE(HttpStatus.UNPROCESSABLE_ENTITY, "CART-003", "구매할 수 없는 상품 옵션입니다."),
    CART_ITEM_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "CART-004", "장바구니에는 최대 99개 상품 옵션만 담을 수 있습니다."),
    CART_ITEMS_REQUIRED(HttpStatus.BAD_REQUEST, "CART-005", "주문할 장바구니 상품을 하나 이상 선택해야 합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
