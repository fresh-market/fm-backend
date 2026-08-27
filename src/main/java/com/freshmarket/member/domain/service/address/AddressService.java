package com.freshmarket.member.domain.service.address;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.Address;
import com.freshmarket.member.domain.repository.AddressRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.dto.AddressCreateRequest;
import com.freshmarket.member.domain.dto.AddressUpdateRequest;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// (2026-08-18 10:49) com.freshmarket.address.domain.service에서 이동 — domain-map.md 기준
// address는 member 도메인 소유 테이블이라 별도 최상위 도메인일 이유가 없었다. 로직 변경 없음.
// (2026-08-18 12:50) docs/api/member.md 기준 AddressErrorCode/AddressException을 없애고
// MemberErrorCode.ADDRESS_FORBIDDEN(MEMBER-003)으로 합쳤다.
// (2026-08-20, FUN-3-03/FUN-3-04) 회원당 배송지 등록 상한을 둔다 — docs/api/member.md에 상한이
// 명시돼 있지 않아 10개로 잡았다.
@Service
@RequiredArgsConstructor
public class AddressService {

    private static final int MAX_ADDRESSES_PER_MEMBER = 10;

    private final AddressRepository addressRepository;
    private final MemberRepository memberRepository;

    // (2026-08-20, API-3-04/API-5-01) 컨트롤러 응답을 PageResponse로 감싸기 위해 Pageable을
    // 받게 바꿨다 — delete()가 쓰는 non-paged findByMemberIdOrderedByDefaultFirst(memberId)는
    // 이 메서드와 무관하게 그대로 둔다(용도가 다르다: "다음 기본 배송지 하나 찾기").
    @Transactional(readOnly = true)
    public Page<Address> findMyAddresses(Long memberId, Pageable pageable) {
        return addressRepository.findByMemberIdOrderedByDefaultFirst(memberId, pageable);
    }

    // (2026-08-21, DI-2-01/DI-3-06) countByMemberId() 후 조건부로 save()하는 방식은 두 요청이
    // 동시에 들어오면 둘 다 상한 미만으로 카운트를 읽고 둘 다 통과해 상한을 넘길 수 있었다.
    // member 행에 비관적 락(findByIdForUpdate)을 먼저 걸어서, 같은 회원의 두 번째 요청은 첫 번째가
    // 끝날 때까지 대기하게 만든다 — "카운트 확인 → 저장"이 사실상 한 회원 기준으로 직렬화된다.
    @Transactional
    public Address create(Long memberId, AddressCreateRequest request) {
        memberRepository.findByIdForUpdate(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        long existingCount = addressRepository.countByMemberId(memberId);
        if (existingCount >= MAX_ADDRESSES_PER_MEMBER) {
            throw new MemberException(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }
        boolean isFirstAddress = existingCount == 0;
        boolean shouldBeDefault = isFirstAddress || request.isDefault();

        if (shouldBeDefault) {
            addressRepository.clearDefaultForMember(memberId);
        }

        Address address = Address.register(
                memberId, request.recipient(), request.phone(), request.zipcode(),
                request.roadAddress(), request.detailAddress(), shouldBeDefault);

        return addressRepository.save(address);
    }

    /*
     * (2026-08-20, SEC-3-02/FUN-3-01) AddressResponse가 recipient/phone을 마스킹해서 내려주므로,
     * 수정 폼이 그 마스킹된 표시값을 그대로 다시 제출하면 진짜 값처럼 저장돼버릴 수 있다.
     * null(안 보낸 필드)은 미변경으로 두고, 마스킹 대상 필드는 거기에 더해
     * PiiMasker.isMaskedEchoOf()로 "마스킹된 값을 그대로 돌려보낸 경우"까지 한 번 더 걸러
     * 미변경 취급한다.
     */
    @Transactional
    public Address update(Long memberId, Long addressId, AddressUpdateRequest request) {
        Address address = getOwned(memberId, addressId);

        String recipient = resolve(request.recipient(), address.getRecipient(), PiiMasker::maskName);
        String phone = resolve(request.phone(), address.getPhone(), PiiMasker::maskPhone);
        String zipcode = request.zipcode() != null ? request.zipcode() : address.getZipcode();
        String roadAddress = request.roadAddress() != null ? request.roadAddress() : address.getRoadAddress();
        String detailAddress = request.detailAddress() != null ? request.detailAddress() : address.getDetailAddress();

        address.update(recipient, phone, zipcode, roadAddress, detailAddress);

        if (Boolean.TRUE.equals(request.isDefault()) && !address.isDefault()) {
            addressRepository.clearDefaultForMember(memberId);
            address.markAsDefault();
        }

        return address;
    }

    private String resolve(String submitted, String current, Function<String, String> masker) {
        if (submitted == null) {
            return current;
        }
        return PiiMasker.isMaskedEchoOf(submitted, current, masker) ? current : submitted;
    }

    @Transactional
    public void delete(Long memberId, Long addressId) {
        Address address = getOwned(memberId, addressId);
        boolean wasDefault = address.isDefault();

        addressRepository.delete(address);

        if (wasDefault) {
            // 방금 지운 게 기본 배송지였다면 남은 것 중 isDefault=true인 게 없다 — 정렬 1순위가
            // 무의미해지고 사실상 createdAt desc로만 고르는 것과 같다(최근 등록 순).
            addressRepository.findByMemberIdOrderedByDefaultFirst(memberId).stream()
                    .findFirst()
                    .ifPresent(Address::markAsDefault);
        }
    }

    private Address getOwned(Long memberId, Long addressId) {
        return addressRepository.findByIdAndMemberId(addressId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.ADDRESS_FORBIDDEN));
    }
}
