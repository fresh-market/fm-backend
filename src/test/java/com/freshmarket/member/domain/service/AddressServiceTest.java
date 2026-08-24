package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.entity.Address;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.repository.AddressRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.dto.AddressCreateRequest;
import com.freshmarket.member.domain.dto.AddressUpdateRequest;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

// (2026-08-18 10:49) com.freshmarket.address.domain.service에서 이동 — 프로덕션 패키지가
// member.domain.service로 옮겨져 TestPlacementTest의 "프로덕션_패키지를_미러링한다" 규칙에 맞춰
// 테스트도 같이 옮겼다.
// (2026-08-20, SEC-3-02/FUN-3-01/FUN-3-03/FUN-3-04) AddressRequest가 AddressCreateRequest/
// AddressUpdateRequest로 갈라지면서 create/update 테스트를 그 시그니처에 맞춰 다시 썼고,
// 등록 상한·마스킹 에코 방어 케이스를 추가했다.
@ExtendWith(MockitoExtension.class)
class AddressServiceTest {

    @Mock
    private AddressRepository addressRepository;

    @Mock
    private MemberRepository memberRepository;

    private AddressService sut;

    @BeforeEach
    void setUp() {
        sut = new AddressService(addressRepository, memberRepository);
        // (DI-2-01/DI-3-06) create()가 addressRepository.countByMemberId() 전에 회원 행을 락으로
        // 먼저 잡는다 — update/delete 테스트는 이 스텁을 안 쓰므로 lenient로 둔다(strict stubbing
        // 미사용 예외 방지).
        lenient().when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(mock(Member.class)));
    }

    @Test
    void 내_배송지_목록을_리포지토리가_정렬한_그대로_돌려준다() {
        // (2026-08-18 18:40) 기본 배송지 우선 정렬은 AddressRepository의 @Query가 한다 —
        // 이 서비스 단위 테스트는 리포지토리가 돌려준 순서를 그대로 전달하는지만 본다. "기본
        // 배송지가 먼저 온다"는 실제 정렬 자체는 목(mock)으로 못 잡으므로 통합 테스트가 봐야 한다.
        // (2026-08-20, API-3-04) 페이지네이션 도입으로 List 대신 Page를 주고받는다.
        Address address = newAddress(1L, false);
        Pageable pageable = PageRequest.of(0, 20);
        when(addressRepository.findByMemberIdOrderedByDefaultFirst(1L, pageable))
                .thenReturn(new PageImpl<>(List.of(address), pageable, 1));

        Page<Address> result = sut.findMyAddresses(1L, pageable);

        assertThat(result.getContent()).containsExactly(address);
    }

    @Test
    void 첫_배송지는_기본으로_요청하지_않아도_기본_배송지가_된다() {
        when(addressRepository.countByMemberId(1L)).thenReturn(0L);
        when(addressRepository.save(any(Address.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AddressCreateRequest request = new AddressCreateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, false);
        Address result = sut.create(1L, request);

        assertThat(result.isDefault()).isTrue();
        verify(addressRepository).clearDefaultForMember(1L);
    }

    @Test
    void 두번째_배송지는_기본으로_요청하지_않으면_기본이_아니다() {
        when(addressRepository.countByMemberId(1L)).thenReturn(1L);
        when(addressRepository.save(any(Address.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AddressCreateRequest request = new AddressCreateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, false);
        Address result = sut.create(1L, request);

        assertThat(result.isDefault()).isFalse();
        verify(addressRepository, never()).clearDefaultForMember(1L);
    }

    @Test
    void 두번째_배송지도_기본으로_요청하면_기존_기본을_해제하고_새_배송지가_기본이_된다() {
        when(addressRepository.countByMemberId(1L)).thenReturn(1L);
        when(addressRepository.save(any(Address.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AddressCreateRequest request = new AddressCreateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, true);
        Address result = sut.create(1L, request);

        assertThat(result.isDefault()).isTrue();
        verify(addressRepository).clearDefaultForMember(1L);
    }

    @Test
    void 이미_상한만큼_등록돼_있으면_예외() {
        when(addressRepository.countByMemberId(1L)).thenReturn(10L);

        AddressCreateRequest request = new AddressCreateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, false);

        assertThatThrownBy(() -> sut.create(1L, request))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
        verify(addressRepository, never()).save(any());
    }

    @Test
    void 존재하지_않는_회원이면_배송지_등록_시_예외() {
        // (DI-2-01/DI-3-06) create()가 count 확인 전에 회원 행을 락으로 먼저 잡는다 — 그 조회가
        // 비면(탈퇴 등으로 회원이 없으면) 카운트를 보지도 않고 바로 실패해야 한다.
        when(memberRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        AddressCreateRequest request = new AddressCreateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, false);

        assertThatThrownBy(() -> sut.create(1L, request))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.MEMBER_NOT_FOUND);
        verify(addressRepository, never()).countByMemberId(any());
        verify(addressRepository, never()).save(any());
    }

    @Test
    void 본인_소유가_아닌_배송지를_수정하려_하면_예외() {
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.empty());

        AddressUpdateRequest request = new AddressUpdateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, false);

        assertThatThrownBy(() -> sut.update(1L, 1L, request))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.ADDRESS_FORBIDDEN);
    }

    @Test
    void 배송지_수정_시_입력값이_반영된다() {
        Address address = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest("새수령인", "010-0000-0000", "54321", "부산시", "101호", false);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.getRecipient()).isEqualTo("새수령인");
        assertThat(result.getPhone()).isEqualTo("010-0000-0000");
        assertThat(result.getZipcode()).isEqualTo("54321");
        assertThat(result.getDetailAddress()).isEqualTo("101호");
    }

    @Test
    void 수정_필드를_안_보내면_기존_값이_그대로_유지된다() {
        // 원본: newAddress()가 recipient="홍길동", phone="010-1234-5678", zipcode="12345",
        // roadAddress="서울시", detailAddress=null 로 등록한다.
        Address address = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest(null, null, null, null, null, null);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.getRecipient()).isEqualTo("홍길동");
        assertThat(result.getPhone()).isEqualTo("010-1234-5678");
        assertThat(result.getZipcode()).isEqualTo("12345");
        assertThat(result.getRoadAddress()).isEqualTo("서울시");
        assertThat(result.isDefault()).isFalse();
        verify(addressRepository, never()).clearDefaultForMember(any());
    }

    @Test
    void 조회_응답이_보여준_마스킹된_전화번호를_그대로_다시_보내면_원래_값이_유지된다() {
        // (SEC-3-02/FUN-3-01) AddressResponse.from()이 010-1234-5678을 010****5678로 마스킹해서
        // 내려주므로, 수정 폼이 그 마스킹값을 그대로 다시 제출한 상황을 재현한다.
        Address address = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest(null, "010****5678", null, null, null, null);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.getPhone()).isEqualTo("010-1234-5678");
    }

    @Test
    void 조회_응답이_보여준_마스킹된_수령인_이름을_그대로_다시_보내면_원래_값이_유지된다() {
        Address address = newAddress(1L, false); // recipient="홍길동" -> 마스킹하면 "홍*동"
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest("홍*동", null, null, null, null, null);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.getRecipient()).isEqualTo("홍길동");
    }

    @Test
    void 진짜로_바뀐_전화번호는_마스킹값과_안_겹치면_그대로_반영된다() {
        Address address = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest(null, "010-9999-0000", null, null, null, null);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.getPhone()).isEqualTo("010-9999-0000");
    }

    @Test
    void 기본이_아니던_배송지를_기본으로_바꾸면_기존_기본을_해제한다() {
        Address address = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, true);
        Address result = sut.update(1L, 1L, request);

        assertThat(result.isDefault()).isTrue();
        verify(addressRepository).clearDefaultForMember(1L);
    }

    @Test
    void 이미_기본인_배송지를_다시_기본으로_요청하면_해제_쿼리를_또_보내지_않는다() {
        Address address = newAddress(1L, true);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(address));

        AddressUpdateRequest request = new AddressUpdateRequest("홍길동", "010-1234-5678", "12345", "서울시", null, true);
        sut.update(1L, 1L, request);

        verify(addressRepository, never()).clearDefaultForMember(1L);
    }

    @Test
    void 본인_소유가_아닌_배송지를_삭제하려_하면_예외() {
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.delete(1L, 1L))
                .isInstanceOf(MemberException.class)
                .extracting(e -> ((MemberException) e).getErrorCode())
                .isEqualTo(MemberErrorCode.ADDRESS_FORBIDDEN);
    }

    @Test
    void 기본_배송지를_삭제하면_남은_배송지_중_첫번째가_새_기본이_된다() {
        Address deleted = newAddress(1L, true);
        Address remaining = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(deleted));
        when(addressRepository.findByMemberIdOrderedByDefaultFirst(1L)).thenReturn(List.of(remaining));

        sut.delete(1L, 1L);

        verify(addressRepository).delete(deleted);
        assertThat(remaining.isDefault()).isTrue();
    }

    @Test
    void 기본이_아닌_배송지를_삭제하면_다른_배송지의_기본_여부를_건드리지_않는다() {
        Address deleted = newAddress(1L, false);
        when(addressRepository.findByIdAndMemberId(1L, 1L)).thenReturn(Optional.of(deleted));

        sut.delete(1L, 1L);

        verify(addressRepository).delete(deleted);
        verify(addressRepository, never()).findByMemberIdOrderedByDefaultFirst(1L);
    }

    private Address newAddress(Long memberId, boolean isDefault) {
        return Address.register(memberId, "홍길동", "010-1234-5678", "12345", "서울시", null, isDefault);
    }
}
