package com.freshmarket.member.domain.service;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.MemberGrade;
import com.freshmarket.member.domain.repository.MemberGradeRepository;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.dto.MemberProfileUpdateRequest;
import com.freshmarket.member.domain.dto.MemberResponse;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// (2026-08-18 13:25) docs/api/member.md 기준 GET/PATCH /v1/members/me를 여기서 함께 담당한다.
// 예전엔 "온보딩 완료"(PATCH /members/me/onboarding, 전체 필드 필수 + 약관동의 체크)와
// "일반 수정"(PATCH /members/me, 전체 필드 필수)이 분리돼 있었는데, 문서엔 PATCH /v1/members/me
// 하나뿐이고 부분 수정이다 — 두 흐름을 하나로 합쳤다(사용자 확인, Member.updateProfile() 주석 참고).
// (2026-08-18 15:10) 브랜치 전환 중 커밋 안 된 상태로 이 파일이 통째로 날아갔던 걸 복구함 —
// 내용 변경 없이 그대로 다시 썼다.
@Service
@RequiredArgsConstructor
public class MemberProfileUpdateService {

    private final MemberRepository memberRepository;
    private final MemberGradeRepository memberGradeRepository;

    @Transactional(readOnly = true)
    public MemberResponse getMyProfile(Long memberId) {
        Member member = findActiveMember(memberId);
        return MemberResponse.from(member, findGrade(member.getMemberGradeId()));
    }

    // 닉네임 중복 방지는 안 한다 — 팀 결정으로 닉네임 유일성 요구사항 자체를 없앴다(2026-08-19).
    // 예전엔 existsByNickname() 선조회로 검사했는데, 이건 GET-then-CHECK 방식이라 동시 요청이
    // 같은 닉네임을 함께 통과하는 레이스가 항상 있었다(DI-3-01) — DB UNIQUE 제약을 걸어 막는
    // 방향도 있었지만, 애초에 닉네임이 안 겹쳐야 할 이유가 없다고 판단해 이 요구사항 자체를 뺐다.
    /*
     * (2026-08-20, SEC-3-02/FUN-3-01) MemberResponse.from()이 name/email/phone을 마스킹해서
     * 내려주므로(본인 조회여도), 수정 폼이 그 마스킹된 표시값을 그대로 다시 제출하면 진짜 값처럼
     * 저장될 수 있다. 마스킹된 값을 그대로 돌려보낸 경우 null로 바꿔서 넘긴다 — Member.updateProfile()이
     * 이미 null=미변경으로 처리하므로 그 로직을 그대로 재사용한다. nickname은 MemberResponse가
     * 마스킹하지 않는 필드라 이 처리 대상이 아니다.
     */
    @Transactional
    public MemberResponse updateProfile(Long memberId, MemberProfileUpdateRequest request) {
        Member member = findActiveMember(memberId);

        String name = keepIfMaskedEcho(request.name(), member.getName(), PiiMasker::maskName);
        String email = keepIfMaskedEcho(request.email(), member.getEmail(), PiiMasker::maskEmail);
        String phone = keepIfMaskedEcho(request.phone(), member.getPhone(), PiiMasker::maskPhone);

        member.updateProfile(name, request.nickname(), email, phone, request.marketingAgreed());

        return MemberResponse.from(member, findGrade(member.getMemberGradeId()));
    }

    private String keepIfMaskedEcho(String submitted, String current, Function<String, String> masker) {
        return PiiMasker.isMaskedEchoOf(submitted, current, masker) ? null : submitted;
    }

    private Member findActiveMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new MemberException(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        return member;
    }

    private MemberGrade findGrade(Long memberGradeId) {
        return memberGradeRepository.findById(memberGradeId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.DEFAULT_MEMBER_GRADE_NOT_FOUND));
    }
}
