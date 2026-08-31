package com.freshmarket.cart.internal.exception;

import com.freshmarket.common.exception.BusinessException;

public class CartException extends BusinessException {

    public CartException(CartErrorCode errorCode) {
        super(errorCode);
    }

    public CartException(CartErrorCode errorCode, Throwable cause) {
        super(errorCode, cause);
    }
}
