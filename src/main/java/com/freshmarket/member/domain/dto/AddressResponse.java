package com.freshmarket.member.domain.dto;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.Address;
import lombok.Builder;

// (2026-08-18 10:49) com.freshmarket.address.dto에서 이동.
// (2026-08-18 13:10) docs/api/member.md 응답 예시 기준으로 필드를 맞췄다: id -> addressId로
// 이름을 바꾸고(문서 예시 그대로), 응답에 없던 createdAt은 뺐다. recipient/phone은 "내 정보 조회"와
// 같은 이유로 마스킹해서 내보낸다(문서 예시가 "홍*동", "010-****-5678"로 마스킹된 값을 보여줌).
@Builder
public record AddressResponse(
        Long addressId,
        String recipient,
        String phone,
        String zipcode,
        String roadAddress,
        String detailAddress,
        boolean isDefault
) {

    public static AddressResponse from(Address address) {
        return AddressResponse.builder()
                .addressId(address.getId())
                .recipient(PiiMasker.maskName(address.getRecipient()))
                .phone(PiiMasker.maskPhone(address.getPhone()))
                .zipcode(address.getZipcode())
                .roadAddress(address.getRoadAddress())
                .detailAddress(address.getDetailAddress())
                .isDefault(address.isDefault())
                .build();
    }
}
