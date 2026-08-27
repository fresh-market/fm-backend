package com.freshmarket.member.infrastructure.kakao.oauth;

import com.freshmarket.member.domain.exception.AuthErrorCode;
import com.freshmarket.member.domain.exception.AuthException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;

// (2026-08-18 12:20) docs/api/auth.md의 "POST /v1/auth/tokens" 처리 중 "state 검증 →
// code/token 교환 → id_token 검증" 구간. 예전엔 Spring Security의 oauth2Login() 필터가
// 이 전체를 대신해줬는데, 그 필터를 안 쓰기로 하면서 직접 짰다.
/**
 * code+state를 받아 검증된 id_token 클레임(Jwt)을 돌려준다. 실패 시 전부 AuthException — 어느
 * 단계에서 실패했는지는 로그로만 구분하고, 응답은 문서가 정한 4개 코드(AUTH-001~003) 중 하나로만
 * 나간다(내부 구현 상세를 클라이언트에 노출하지 않는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KakaoIdTokenExchanger {

    private static final String REGISTRATION_ID = "kakao";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final KakaoLoginStateRepository kakaoLoginStateRepository;
    private final WebClient kakaoApiWebClient;
    private final JwtDecoder kakaoJwtDecoder;

    public Jwt exchange(String authorizationCode, String state) {
        String expectedNonce = kakaoLoginStateRepository.consume(state);
        if (expectedNonce == null) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=STATE_NOT_FOUND_OR_EXPIRED");
            throw new AuthException(AuthErrorCode.STATE_MISMATCH);
        }

        KakaoTokenResponse tokenResponse = requestToken(authorizationCode);

        Jwt idToken;
        try {
            idToken = kakaoJwtDecoder.decode(tokenResponse.idToken());
        } catch (JwtException e) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=ID_TOKEN_VERIFICATION_FAILED", e);
            throw new AuthException(AuthErrorCode.ID_TOKEN_INVALID, e);
        }

        String actualNonce = idToken.getClaimAsString("nonce");
        if (!expectedNonce.equals(actualNonce)) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=NONCE_MISMATCH");
            throw new AuthException(AuthErrorCode.STATE_MISMATCH);
        }

        return idToken;
    }

    private KakaoTokenResponse requestToken(String authorizationCode) {
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);

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
