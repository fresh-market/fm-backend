package com.freshmarket.member;

// 다른 도메인(주로 order)이 주문 시점에 배송지를 스냅샷 뜰 때 필요한 읽기 전용 값이다.
// roadAddress/detailAddress를 분리해서 주지만, orders.ship_address는 한 컬럼(VARCHAR)이라
// 호출부가 필요하면 "roadAddress + \" \" + detailAddress"로 합쳐서 저장한다 — 이 DTO 자체는
// 합치지 않는다(주소를 그대로 보여줘야 할 다른 용도가 나중에 생길 수 있어서 원본 형태를 유지).
public record AddressInfo(
        Long addressId,
        String recipient,
        String phone,
        String zipcode,
        String roadAddress,
        String detailAddress
) {
}
