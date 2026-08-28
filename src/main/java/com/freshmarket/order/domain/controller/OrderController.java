package com.freshmarket.order.domain.controller;

import com.freshmarket.common.auth.CustomUserDetails;
import com.freshmarket.common.response.PageResponse;
import com.freshmarket.common.response.ResponseEnvelope;
import com.freshmarket.order.domain.dto.OrderCreateRequest;
import com.freshmarket.order.domain.dto.OrderCreateResponse;
import com.freshmarket.order.domain.dto.OrderDetailResponse;
import com.freshmarket.order.domain.dto.OrderListItemResponse;
import com.freshmarket.order.domain.dto.OrderSearchCondition;
import com.freshmarket.order.domain.entity.OrderStatus;
import com.freshmarket.order.domain.service.OrderCreateService;
import com.freshmarket.order.domain.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
@Tag(name = "주문", description = "내 주문 목록과 상세 조회")
class OrderController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderService orderService;
    private final OrderCreateService orderCreateService;

    @PostMapping
    public ResponseEntity<ResponseEnvelope<OrderCreateResponse>> createOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderCreateService.createOrder(userDetails.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseEnvelope.success(response));
    }

    @GetMapping
    @Operation(summary = "내 주문 목록 조회", description = "주문 상태와 주문 일자로 필터링해 페이지 단위로 조회한다. 페이지 크기는 최대 100이다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    public ResponseEntity<ResponseEnvelope<PageResponse<OrderListItemResponse>>> getOrders(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable
    ) {
        Pageable boundedPageable = PageRequest.of(pageable.getPageNumber(),
                Math.min(pageable.getPageSize(), MAX_PAGE_SIZE), pageable.getSort());
        Page<OrderListItemResponse> page = orderService.getOrders(userDetails.getId(),
                        new OrderSearchCondition(status, from, to), boundedPageable)
                .map(OrderListItemResponse::from);
        return ResponseEntity.ok(ResponseEnvelope.success(PageResponse.from(page)));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "주문 상세 조회", description = "현재 회원이 소유한 주문의 상세와 주문 상품을 조회한다.")
    @ApiResponse(responseCode = "200", description = "조회 성공")
    @ApiResponse(responseCode = "404", description = "주문이 없거나 현재 회원의 주문이 아님")
    public ResponseEntity<ResponseEnvelope<OrderDetailResponse>> getOrder(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(ResponseEnvelope.success(
                orderService.getOrder(userDetails.getId(), orderId)));
    }
}
