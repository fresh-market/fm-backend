package com.freshmarket.member.internal.oauth;

import com.freshmarket.member.internal.client.KakaoTokenClient;
import com.freshmarket.member.internal.exception.AuthErrorCode;
import com.freshmarket.member.internal.exception.AuthException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

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
    private final KakaoTokenClient kakaoTokenClient;
    private final JwtDecoder kakaoJwtDecoder;

    public Jwt exchange(String authorizationCode, String state) {
        String expectedNonce = kakaoLoginStateRepository.consume(state);
        if (expectedNonce == null) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=STATE_NOT_FOUND_OR_EXPIRED");
            throw new AuthException(AuthErrorCode.STATE_MISMATCH);
        }

        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        KakaoTokenResponse tokenResponse = requestToken(kakao, authorizationCode);

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

    /*
     * kakaoTokenClient.exchangeToken()은 @CircuitBreaker(name="kakaoLogin")가 걸린 빈
     * 경계 너머 호출이라, 서킷이 열려있으면 그 메서드 본문(WebClientException catch)을 타지도
     * 못하고 CallNotPermittedException이 여기 호출부로 바로 튀어나온다 — 그 안의
     * catch(WebClientException)로는 못 잡으므로 여기서 별도로 잡아 같은 AuthException으로
     * 통일해준다(호출하는 입장에선 "카카오를 못 쓴다"는 사실 자체는 같으니까).
     */
    private KakaoTokenResponse requestToken(ClientRegistration kakao, String authorizationCode) {
        try {
            return kakaoTokenClient.exchangeToken(kakao, authorizationCode);
        } catch (CallNotPermittedException e) {
            log.warn("event=KAKAO_LOGIN_FAILED reason=CIRCUIT_OPEN");
            throw new AuthException(AuthErrorCode.KAKAO_UNAVAILABLE, e);
        }
    }
}
