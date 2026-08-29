package com.freshmarket.member.domain;

import com.freshmarket.member.AddressInfo;
import com.freshmarket.member.MemberApi;
import com.freshmarket.member.MemberInfo;
import com.freshmarket.member.domain.entity.Address;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.MemberStatus;
import com.freshmarket.member.domain.repository.AddressRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 공개 API는 리포지토리 조회 결과를 외부 계약 타입(MemberInfo/AddressInfo)으로만 변환해서 전달한다.
// 트랜잭션은 여기서 안 연다 — 읽기 전용 단건 조회라 트랜잭션이 굳이 필요 없고, ArchitectureTest의
// ApiImpl_에_트랜잭션이_없다 규칙과도 맞는다.
@Component
@RequiredArgsConstructor
class MemberApiImpl implements MemberApi {

    private final MemberRepository memberRepository;
    private final AddressRepository addressRepository;

    @Override
    public Optional<MemberInfo> findMember(Long memberId) {
        return memberRepository.findById(memberId).map(MemberApiImpl::toMemberInfo);
    }

    @Override
    public Optional<AddressInfo> findAddress(Long addressId, Long memberId) {
        return addressRepository.findByIdAndMemberId(addressId, memberId).map(MemberApiImpl::toAddressInfo);
    }

    private static MemberInfo toMemberInfo(Member member) {
        return new MemberInfo(
                member.getId(),
                member.getEmail(),
                member.getName(),
                member.getMemberGradeId(),
                member.getStatus() == MemberStatus.ACTIVE);
    }

    private static AddressInfo toAddressInfo(Address address) {
        return new AddressInfo(
                address.getId(),
                address.getRecipient(),
                address.getPhone(),
                address.getZipcode(),
                address.getRoadAddress(),
                address.getDetailAddress());
    }
}
