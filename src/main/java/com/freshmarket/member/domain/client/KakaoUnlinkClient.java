package com.freshmarket.member.domain.client;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.domain.exception.MemberErrorCode;
import com.freshmarket.member.domain.exception.MemberException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
            log.error("event=KAKAO_UNLINK_FAILED status={} bodyLength={} body={} kakaoUserId={}",
                    e.getStatusCode(), rawBody == null ? 0 : rawBody.length(),
                    PiiMasker.redact(rawBody), PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILED, e);
        } catch (Exception e) {
            log.error("event=KAKAO_UNLINK_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_UNLINK_FAILED, e);
        }
    }

}
