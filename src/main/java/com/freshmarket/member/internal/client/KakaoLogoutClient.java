package com.freshmarket.member.internal.client;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.internal.exception.MemberErrorCode;
import com.freshmarket.member.internal.exception.MemberException;
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

/** 서버(Admin Key) 주도로 카카오 쪽 access token을 무효화한다(연결 자체는 유지). */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoLogoutClient {

    private static final String LOGOUT_URL = "https://kapi.kakao.com/v1/user/logout";

    private final WebClient kakaoApiWebClient;

    @Value("${kakao.admin-key}")
    private String adminKey;

    /*
     * (2026-08-27) 예전엔 여기서 예외를 로그만 남기고 삼켰다 — @CircuitBreaker는 메서드가
     * 예외를 던지며 끝났는지로만 성공/실패를 판단하는데, 삼키면 이 메서드는 항상 "정상 종료"로
     * 보여서 카카오가 계속 실패해도 서킷의 실패율 카운터에 아무것도 안 쌓인다. unlink와 똑같이
     * 실패를 밖으로 던지고, 로그아웃 자체는 실패해도 아웃박스까지는 안 두기로 했으므로(우리 쪽
     * 세션/토큰은 이미 정리된 뒤라 unlink만큼 정합성이 급하지 않음) 이 예외를 받아서 로그만
     * 남기고 끝내는 역할은 KakaoLogoutEventListener로 옮겼다.
     */
    @CircuitBreaker(name = "kakaoLogout")
    public void logout(String kakaoUserId) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("target_id_type", "user_id");
        form.add("target_id", kakaoUserId);

        try {
            kakaoApiWebClient.post()
                    .uri(LOGOUT_URL)
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (WebClientResponseException e) {
            // 바디를 그대로 찍지 않는 이유는 KakaoUnlinkClient와 동일(SEC-4-02) — 카카오 쪽
            // 자유 형식 에러 메시지라 통제 불가하므로 통째로 가린다.
            String rawBody = e.getResponseBodyAsString();
            log.warn("event=KAKAO_LOGOUT_FAILED status={} bodyLength={} body={} kakaoUserId={}",
                    e.getStatusCode(), rawBody == null ? 0 : rawBody.length(),
                    PiiMasker.redact(rawBody), PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_LOGOUT_FAILED, e);
        } catch (Exception e) {
            log.warn("event=KAKAO_LOGOUT_FAILED kakaoUserId={}", PiiMasker.maskProviderId(kakaoUserId), e);
            throw new MemberException(MemberErrorCode.KAKAO_LOGOUT_FAILED, e);
        }
    }
}
