package com.freshmarket.cart.domain.controller;

import com.freshmarket.cart.domain.dto.CartItemCreateRequest;
import com.freshmarket.cart.domain.dto.CartItemResponse;
import com.freshmarket.cart.domain.dto.CartItemUpdateRequest;
import com.freshmarket.cart.domain.dto.CartResponse;
import com.freshmarket.cart.domain.service.CartService;
import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.ResponseEnvelope;
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
class CartController {

    private final CartService cartService;

    @GetMapping
    public ResponseEntity<ResponseEnvelope<CartResponse>> getCart(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ResponseEntity.ok(ResponseEnvelope.success(cartService.getCart(userDetails.getId())));
    }

    @PostMapping("/items")
    public ResponseEntity<ResponseEnvelope<CartItemResponse>> addItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestBody @Valid CartItemCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseEnvelope.success(cartService.addItem(userDetails.getId(), request)));
    }

    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<ResponseEnvelope<CartItemResponse>> updateItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long cartItemId,
            @RequestBody @Valid CartItemUpdateRequest request) {
        return ResponseEntity.ok(ResponseEnvelope.success(cartService.updateItem(userDetails.getId(), cartItemId, request)));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<Void> deleteItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long cartItemId) {
        cartService.deleteItem(userDetails.getId(), cartItemId);
        return ResponseEntity.noContent().build();
    }
}
