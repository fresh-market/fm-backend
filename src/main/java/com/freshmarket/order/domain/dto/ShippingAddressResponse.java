package com.freshmarket.order.domain.dto;

import com.freshmarket.order.domain.entity.Order;

// 주소록 원본이 아니라 주문 당시의 배송지 스냅샷을 반환한다.
public record ShippingAddressResponse(
        String recipient,
        String phone,
        String zipcode,
        String address
) {
    public static ShippingAddressResponse from(Order order) {
        return new ShippingAddressResponse(order.getShipRecipient(), order.getShipPhone(),
                order.getShipZipcode(), order.getShipAddress());
    }
}
