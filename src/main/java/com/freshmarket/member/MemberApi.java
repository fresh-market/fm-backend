package com.freshmarket.member;

import java.util.Optional;

// 다른 도메인이 회원/배송지 정보를 조회할 때 쓰는 member 도메인의 공개 창구다.
public interface MemberApi {

    Optional<MemberInfo> findMember(Long memberId);

    // memberId까지 받는 이유는 소유권 검증까지 이 메서드가 책임지기 위함이다 — 남의 addressId를
    // 넣으면(주인이 다르면) 값이 있어도 empty를 준다. AddressService.getOwned()와 같은 조건이다.
    Optional<AddressInfo> findAddress(Long addressId, Long memberId);
}
