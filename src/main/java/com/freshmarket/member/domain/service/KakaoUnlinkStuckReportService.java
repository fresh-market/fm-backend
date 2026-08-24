package com.freshmarket.member.domain.service;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/*
 * (2026-08-24) KakaoUnlinkRetryService.retryAllPending()은 shouldGiveUp()인 행(포기 문턱을 넘은
 * 실패)은 더 이상 카카오를 부르지 않는다 — 재시도해도 성공할 가능성이 낮다고 판단된 행이라 API
 * 호출을 낭비하지 않으려는 것이다. 그 대신 그런 행이 여전히 쌓여 있다는 걸 사람에게 알리는 역할을
 * 이 클래스가 맡는다. 카카오를 부르지도, DB를 쓰지도 않아(순수 조회 + 로그) @Transactional이
 * 필요 없다.
 *
 * 재시도(KakaoUnlinkRetryScheduler, 10분)와 이 리포트(KakaoUnlinkStuckReportScheduler, 1일)의
 * 주기를 다르게 가져가는 이유: 재시도는 "카카오가 방금 회복됐을 수도 있다"를 빠르게 잡아내는 게
 * 목적이라 짧을수록 좋지만, 이 리포트는 "재시도해도 어차피 안 되니 사람이 봐야 한다"가 목적이라
 * 10분마다 같은 얘기를 반복해봐야 알림 피로만 쌓인다 — 실제로 우리 DB는 이미 WITHDRAWN 처리가
 * 끝난 뒤라(회원 관점에서 즉시 위험한 상태가 아니다) 사람이 반응할 수 있는 속도에 맞춰도 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoUnlinkStuckReportService {

    private static final int MEMBER_ID_LOG_LIMIT = 50;

    private final KakaoUnlinkFailureRepository failureRepository;

    /** 미해소 포기 건의 memberId 목록을 반환하고, 하나라도 있으면 요약을 ERROR로 남긴다. */
    public List<Long> reportStuck() {
        List<KakaoUnlinkFailure> stuck = failureRepository
                .findByAttemptCountGreaterThanEqualAndResolvedFalse(KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS);
        if (stuck.isEmpty()) {
            return List.of();
        }

        List<Long> memberIds = stuck.stream()
                .map(KakaoUnlinkFailure::getMemberId)
                .toList();
        // memberId는 우리 내부 식별자라 PII 마스킹 대상이 아니다(kakaoUserId와 다르다).
        String loggedMemberIds = memberIds.stream()
                .limit(MEMBER_ID_LOG_LIMIT)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        log.error("event=KAKAO_UNLINK_OUTBOX_STUCK_SUMMARY count={} memberIds={} truncated={}",
                stuck.size(), loggedMemberIds, memberIds.size() > MEMBER_ID_LOG_LIMIT);
        return memberIds;
    }
}
