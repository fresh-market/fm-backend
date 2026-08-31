package com.freshmarket.member.internal.client;

import com.freshmarket.common.logging.PiiMasker;
import com.freshmarket.member.internal.exception.AuthErrorCode;
import com.freshmarket.member.internal.exception.AuthException;
import com.freshmarket.member.internal.oauth.KakaoTokenResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 카카오 OIDC 토큰교환(POST {tokenUri}) 호출 전용 클라이언트.
 *
 * (2026-08-27) KakaoIdTokenExchanger.requestToken()에 있던 걸 여기로 뺐다 — 원래 private
 * 메서드로 같은 클래스 안에서 self-invocation됐는데, @CircuitBreaker는 스프링 AOP 프록시
 * 기반이라 self-invocation은 프록시를 안 거쳐서 어노테이션을 붙여도 조용히 아무 효과가 없다
 * (KakaoUnlinkClient/KakaoLogoutClient처럼 별도 빈이어야 실제로 걸린다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KakaoTokenClient {

    private final WebClient kakaoApiWebClient;

    @CircuitBreaker(name = "kakaoLogin")
    public KakaoTokenResponse exchangeToken(ClientRegistration kakao, String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakao.getClientId());
        form.add("client_secret", kakao.getClientSecret());
        form.add("redirect_uri", kakao.getRedirectUri());
        form.add("code", authorizationCode);

        try {
            return kakaoApiWebClient.post()
                    .uri(kakao.getProviderDetails().getTokenUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .bodyValue(form)
                    .retrieve()
                    .bodyToMono(KakaoTokenResponseBody.class)
                    .map(KakaoTokenResponseBody::toRecord)
                    .block();
        } catch (WebClientResponseException e) {
            // 카카오가 응답은 줬지만 거절한 경우(예: invalid_grant, 만료된 code) — 연결 자체가
            // 안 된 것과는 원인이 다르므로 상태코드를 남긴다. 바디는 unlink/logout과 같은 이유로
            // (SEC-4-02) 통째로 가린다.
            String rawBody = e.getResponseBodyAsString();
            log.warn("event=KAKAO_LOGIN_FAILED reason=TOKEN_EXCHANGE_REJECTED status={} bodyLength={} body={}",
                    e.getStatusCode(), rawBody == null ? 0 : rawBody.length(), PiiMasker.redact(rawBody), e);
            throw new AuthException(AuthErrorCode.KAKAO_UNAVAILABLE, e);
        } catch (WebClientException e) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=TOKEN_ENDPOINT_UNREACHABLE", e);
            throw new AuthException(AuthErrorCode.KAKAO_UNAVAILABLE, e);
        }
    }

    // 카카오 토큰 응답의 snake_case 필드명을 그대로 받기 위한 매핑 전용 타입. KakaoTokenResponse
    // 레코드를 Jackson이 바로 못 읽어서(camelCase 프로퍼티명이 안 맞음) 이 클래스를 거쳐 변환한다.
    private record KakaoTokenResponseBody(
            String token_type, String access_token, String id_token, Long expires_in) {
        KakaoTokenResponse toRecord() {
            return new KakaoTokenResponse(token_type, access_token, id_token, expires_in);
        }
    }
}
