package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.entity.Member;
import com.freshmarket.member.domain.entity.SocialType;
import com.freshmarket.member.domain.oauth.KakaoIdTokenExchanger;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.exception.AuthErrorCode;
import com.freshmarket.member.domain.exception.AuthException;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// docs/api/member.md 기준 "탈퇴 전 카카오 재인증" 요구사항을 검증 단계로 구현한다.
/**
 * 회원탈퇴 유스케이스. 순서: 0) 카카오 재인증(id_token) 검증 — 본인 계정인지 확인
 * 1) DB 상태 변경(WITHDRAWN) 2) refreshToken 삭제 3) accessTokenValidAfter 커트라인 등록
 * 4) 카카오 unlink는 AFTER_COMMIT 이벤트로 미룸(KakaoUnlinkEventListener).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawalService {

    private static final String SUBJECT_CLAIM = "sub";

    private final MemberRepository memberRepository;
    private final MemberTokenService memberTokenService;
    private final KakaoIdTokenExchanger kakaoIdTokenExchanger;
    private final MemberWithdrawalCompletionService memberWithdrawalCompletionService;

    // (2026-08-19) 예전엔 이 메서드 전체가 @Transactional이었다 — 카카오 재인증(동기 네트워크
    // 호출)까지 DB 트랜잭션 안에서 일어나 응답 대기 동안 커넥션이 묶였다(DI-4-02). login()과
    // 달리 여기는 verifyReauth() 뒤에 member.withdraw()로 이미 로드한 엔티티를 직접 바꾸는
    // dirty-checking 방식이라 "@Transactional만 떼기"가 안 통했다 — 그래서 그 쓰기 부분
    // (Member.withdraw() 대신 MemberRepository.markWithdrawn()으로 명시적 UPDATE) 자체를
    // MemberWithdrawalCompletionService로 옮기고, 이 메서드는 카카오 호출까지만 담당하는
    // 비트랜잭션 진입점이 됐다. 상세 이유는 MemberWithdrawalCompletionService의 클래스 주석 참고.
    public void withdraw(Long memberId, String reason, String authorizationCode, String state) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.MEMBER_NOT_FOUND));

        if (member.isWithdrawn()) {
            throw new MemberException(MemberErrorCode.MEMBER_ALREADY_WITHDRAWN);
        }

        // TODO(주문 도메인 추가 시): 진행 중 주문/미완료 환불이 있으면 여기서 막아야 한다
        // (MEMBER-001/MEMBER-002).

        verifyReauth(member, authorizationCode, state);

        memberWithdrawalCompletionService.complete(memberId, member.getProviderUserId(), member.getRole().name(), reason);
    }

    // GET /v1/auth/kakao/authorize?reauth=true 로 받은 code/state를 로그인 때와 같은 방식으로
    // 검증한다(state/nonce/서명/발급자 전부 재사용). 재인증 자체는 성공했더라도 다른 카카오
    // 계정으로 재로그인했다면 본인 확인이 안 된 것이므로 AUTH-005로 별도 구분한다.
    private void verifyReauth(Member member, String authorizationCode, String state) {
        Jwt idToken = kakaoIdTokenExchanger.exchange(authorizationCode, state);
        String reauthenticatedProviderUserId = idToken.getClaimAsString(SUBJECT_CLAIM);

        if (!member.getProviderUserId().equals(reauthenticatedProviderUserId)) {
            throw new AuthException(AuthErrorCode.REAUTH_ACCOUNT_MISMATCH);
        }
    }

    /** 카카오 쪽에서 먼저 연결을 끊은 경우(웹훅으로 통보) — DB 상태만 맞추고 unlink는 다시 호출하지 않는다. */
    @Transactional
    public void withdrawByKakaoWebhook(String kakaoUserId) {
        String activeProviderKey = Member.buildActiveProviderKey(SocialType.KAKAO, kakaoUserId);

        memberRepository.findByActiveProviderKey(activeProviderKey)
                .ifPresent(member -> {
                    member.withdraw();
                    memberTokenService.revoke(member.getId(), member.getRole().name(), false);
                });
        // 회원이 없거나 이미 탈퇴 상태여도 예외를 던지지 않는다 — 웹훅 응답은 무조건 200이어야 한다.
    }
}
