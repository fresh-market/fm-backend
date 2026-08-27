package com.freshmarket.member.domain.client;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.exception.KakaoUnlinkRejectedException;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** 서버(Admin Key) 주도로 카카오 계정과의 연결을 끊는 클라이언트. */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoUnlinkClient {

    private static final String UNLINK_URL = "https://kapi.kakao.com/v1/user/unlink";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

    @CircuitBreaker(name = "kakaoUnlink")
    public void unlink(String kakaoUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);

        try {
            kakaoApiWebClient.post()
                    .uri(UNLINK_URL)
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            // (2026-08-21, FUN-2-01/FUN-2-02) 예전엔 이 블록이 로그만 남기고 삼켰다 — 그러면
            // KakaoUnlinkEventListener/KakaoUnlinkRetryService 양쪽 다 unlink()가 정상 반환한 것으로
            // 보고 재시도도, kakao_unlink_failure 아웃박스 기록도 안 하고 넘어간다. 회원은 WITHDRAWN,
            // 카카오는 연결이 살아있는 상태로 영구히 어긋나는데 아무도 그걸 감지 못하는 것과 같다.
            // 카카오 쪽 4xx/5xx도 다른 예외와 똑같이 실패로 취급해서 던져야 그 흐름들을 탄다.
            //
            // (SEC-4-02) 카카오 응답 바디를 그대로 찍으면 이 클래스만 HttpBodyLoggingFilter/
            // ExternalApiLoggingExchangeFilter의 마스킹 인프라를 우회해서 새는 경로가 된다. 바디는
            // 카카오 쪽 자유 형식 에러 메시지라 어떤 값이 실릴지 우리가 통제할 수 없으므로(부분
            // 마스킹이 애매한 경우, 다른 필터들의 REDACTED 관례와 동일하게) 통째로 가린다. 상태코드와
            // 바디 길이만 남겨도 "카카오가 몇 번대 에러로 몇 바이트짜리 응답을 줬는지"는 추적 가능하다.
            String rawBody = e.getResponseBodyAsString();
            if (isNonRetryableRejection(e.getStatusCode())) {
                // (2026-08-27, PR 리뷰 P1) 429를 뺀 4xx는 카카오가 "정상적으로 거절"한 응답이라
                // 재시도해도 결과가 똑같다 — 이걸 KAKAO_UNLINK_FAILED와 똑같이 취급해서 리스너
                // 1회 + 스케줄러 최대 5회짜리 자동 재시도를 태우면, 사람이 봐야 할 설정 실수
                // (예: Admin Key 만료)를 6번이나 헛되이 두드린 뒤에야 포기 처리로 넘어간다.
                // 별도 예외로 던져서 즉시 수동 처리 대상으로 보낸다.
                log.error("event=KAKAO_UNLINK_REJECTED status={} bodyLength={} body={} kakaoUserId={} "
                                + "— 재시도 없이 즉시 수동 처리 대상",
                        e.getStatusCode(), rawBody == null ? 0 : rawBody.length(),
                        PiiMasker.redact(rawBody), PiiMasker.maskProviderId(kakaoUserId), e);
                throw new KakaoUnlinkRejectedException(e);
            }
            log.error("event=KAKAO_UNLINK_FAILED status={} bodyLength={} body={} kakaoUserId={}",
                    e.getStatusCode(), rawBody == null ? 0 : rawBody.length(),
                    PiiMasker.redact(rawBody), PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILED, e);
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILED, e);
        }
    }

    /**
     * 429(Admin Key 앱 전체 쿼터)와 5xx는 일시적일 수 있어 재시도 대상으로 남긴다. 그 외 4xx는
     * 카카오가 요청 자체를 거절한 것이라 재시도해도 결과가 같다.
     *
     * KakaoCircuitBreakerConfig.isCircuitFailure()와 판단 기준이 겹쳐 보이지만 용도가 다르다 —
     * 그쪽은 "이 실패가 서킷을 열지"를 정하고, 이건 "unlink 재시도 아웃박스가 이 실패를 자동
     * 재시도할지"를 정한다. package-private으로 열어둔 건 WebClient를 목킹하지 않고도 이 판단
     * 로직만 단위 테스트하기 위해서다.
     */
    static boolean isNonRetryableRejection(HttpStatusCode status) {
        return status.is4xxClientError() && status.value() != 429;
    }
}
