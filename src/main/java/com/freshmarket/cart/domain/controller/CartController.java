package com.freshmarket.cart.domain.controller;

import com.freshmarket.cart.domain.dto.CartItemCreateRequest;
import com.freshmarket.cart.domain.dto.CartItemResponse;
import com.freshmarket.cart.domain.dto.CartItemUpdateRequest;
import com.freshmarket.cart.domain.dto.CartResponse;
import com.freshmarket.cart.domain.service.CartService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/carts/me")
@RequiredArgsConstructor
@Tag(name = "장바구니", description = "내 장바구니 조회와 상품 관리")
class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "장바구니 조회", description = "현재 회원의 장바구니와 담긴 상품을 조회한다. 판매 불가 상품도 현재 상태와 함께 반환한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public ResponseEntity<ResponseEnvelope<CartResponse>> getCart(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseEnvelope.success(cartService.getCart(userDetails.getId())));
    }

    @PostMapping("/items")
    @Operation(summary = "장바구니 상품 추가", description = "판매 가능한 옵션을 장바구니에 담는다. 이미 담긴 옵션이면 수량을 합산한다.")
    @ApiResponse(responseCode = "201", description = "추가 성공")
    @ApiResponse(responseCode = "422", description = "판매 불가 옵션이거나 장바구니 상품 수 상한을 초과함")
    public ResponseEntity<ResponseEnvelope<CartItemResponse>> addItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CartItemCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(cartService.addItem(userDetails.getId(), request)));
    }

    @PatchMapping("/items/{cartItemId}")
    @Operation(summary = "장바구니 상품 수량 변경")
    @ApiResponse(responseCode = "200", description = "수정 성공")
    @ApiResponse(responseCode = "404", description = "장바구니 상품을 찾을 수 없음")
    public ResponseEntity<ResponseEnvelope<CartItemResponse>> updateItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long cartItemId,
            @RequestBody @Valid CartItemUpdateRequest request) {
        return ResponseEntity.ok(ResponseEnvelope.success(cartService.updateItem(userDetails.getId(), cartItemId, request)));
    }

    @DeleteMapping("/items/{cartItemId}")
    @Operation(summary = "장바구니 상품 삭제")
    @ApiResponse(responseCode = "204", description = "삭제 성공")
    @ApiResponse(responseCode = "404", description = "장바구니 상품을 찾을 수 없음")
    public ResponseEntity<Void> deleteItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long cartItemId) {
        cartService.deleteItem(userDetails.getId(), cartItemId);
        return ResponseEntity.noContent().build();
    }
}
