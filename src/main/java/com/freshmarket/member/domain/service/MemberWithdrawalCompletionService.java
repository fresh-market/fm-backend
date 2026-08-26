package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.event.MemberWithdrawalEvent;
import com.freshmarket.member.domain.repository.MemberRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// (2026-08-19) MemberWithdrawalService.withdraw()에서 카카오 재인증(동기 호출)을 트랜잭션 밖으로
// 빼내면서(DI-4-02), 그 뒤에 남는 "DB 상태 변경 + 토큰 폐기 + unlink 이벤트 발행"을 하나의
// 트랜잭션으로 묶어줄 별도 빈이 필요해 만들었다 — 같은 클래스 안에서 @Transactional 메서드를
// this.xxx()로 부르면 프록시를 안 타 트랜잭션이 무시되는 self-invocation 문제 때문에, 이 세
// 단계는 withdraw()가 속한 MemberWithdrawalService 안에 그대로 둘 수 없다.
// MemberWithdrawalEvent는 KakaoUnlinkEventListener가 AFTER_COMMIT에만 받는데(fallbackExecution
// 기본값 false), 이 리스너는 발행 시점에 활성 트랜잭션이 없으면 이벤트를 그냥 버린다 — 그래서
// publishEvent() 호출이 반드시 이 클래스의 @Transactional 안에서 일어나야 한다.
/** MemberWithdrawalService.withdraw()의 카카오 재인증 이후 DB 쓰기 구간만 담당하는 내부 협력자. */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberWithdrawalCompletionService {

    private final MemberRepository memberRepository;
    private final MemberTokenService memberTokenService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void complete(Long memberId, String kakaoUserId, String role, String reason) {
        // reason을 담을 컬럼이 스키마에 없다(V1__init_schema.sql 기준) — 우선 로그로만 남긴다.
        // 감사(audit) 목적으로 영구 보관이 필요해지면 별도 이력 테이블/마이그레이션이 필요하다.
        log.info("event=MEMBER_WITHDRAWN memberId={} reason={}", memberId, reason);

        memberRepository.markWithdrawn(memberId, LocalDateTime.now());
        memberTokenService.revoke(memberId, role, false);
        eventPublisher.publishEvent(new MemberWithdrawalEvent(memberId, kakaoUserId));
    }
}
