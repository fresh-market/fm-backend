package com.freshmarket.member.domain.dto;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.MemberGrade;
import com.freshmarket.member.domain.entity.MemberStatus;

// (2026-08-18 13:25) docs/api/member.md의 "내 정보 조회" 응답 예시 기준으로 전면 재작성.
// - name/email/phone은 본인 조회여도 마스킹해서 내보낸다(문서 예시가 마스킹된 값을 보여줌).
// - grade(gradeId+name)를 추가했다 — Member는 memberGradeId(Long)만 갖고 있어서 호출하는
//   서비스가 MemberGradeRepository로 조회해 같이 넘겨줘야 한다.
// - role/createdAt/address는 문서 응답 예시에 없어서 뺐다(address는 별도 Address API).
// (2026-08-18 15:10) 브랜치 전환 중 커밋 안 된 상태로 이 파일이 통째로 날아갔던 걸 복구함 —
// 내용 변경 없이 그대로 다시 썼다.
public record MemberResponse(
        Long memberId,
        String nickname,
        String name,
        String email,
        String phone,
        MemberStatus status,
        GradeSummary grade,
        boolean marketingAgreed
) {

    public record GradeSummary(Long gradeId, String name) {
        public static GradeSummary from(MemberGrade grade) {
            return new GradeSummary(grade.getId(), grade.getName());
        }
    }

    public static MemberResponse from(Member member, MemberGrade grade) {
        return new MemberResponse(
                member.getId(),
                member.getNickname(),
                PiiMasker.maskName(member.getName()),
                PiiMasker.maskEmail(member.getEmail()),
                PiiMasker.maskPhone(member.getPhone()),
                member.getStatus(),
                GradeSummary.from(grade),
                member.isMarketingAgreed()
        );
    }
}
