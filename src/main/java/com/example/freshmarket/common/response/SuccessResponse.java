package com.example.freshmarket.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuccessResponse<T>(String code, String message, T data) {
    public static <T> SuccessResponse<T> of(T data) {
        return new SuccessResponse<>("SUCCESS", "요청이 성공적으로 처리되었습니다.", data);
    }
}
