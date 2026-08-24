package com.freshmarket.member.domain.oauth;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

// (2026-08-18 12:15) docs/api/auth.md의 "GET /v1/auth/kakao/authorize" 구현.
/**
 * state/nonce를 만들어 Redis에 잠깐 저장해두고(KakaoLoginStateRepository), 프론트가 그대로
 * window.location으로 이동시키기만 하면 되는 카카오 인가 URL을 조립해서 돌려준다.
 *
 * authorization-uri도 client-id/redirect-uri처럼 application.yml에 새로 안 적고
 * ClientRegistrationRepository(OIDC discovery로 스프링이 이미 채워둔 값)에서 가져다 쓴다.
 */
@Service
@RequiredArgsConstructor
public class KakaoAuthorizationService {

    private static final String REGISTRATION_ID = "kakao";

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final KakaoLoginStateRepository kakaoLoginStateRepository;

    // (2026-08-18 13:40) docs/api/member.md의 탈퇴 전 "카카오 재인증" 요구사항을 위해 파라미터를
    // 추가했다(원래는 무인자 버전 하나였다 — 호출부(MemberLoginService)가 항상 명시적으로
    // forceReauth를 넘기게 되면서 무인자 오버로드는 정리했다).
    // 문서엔 GET /v1/auth/kakao/authorize에 별도 파라미터가 없지만, 로그인 시작 경로 자체는
    // 그대로 재사용하고(같은 인가 URL 조립 로직) prompt=login만 얹어 카카오 세션이 남아있어도
    // 강제로 재로그인하게 만든다 — 새 엔드포인트를 만들지 않고 기존 경로를 넓혀 쓰는 선택
    // (사용자 확인: "탈퇴 요청에 신선 id_token 포함"). forceReauth=true로 받은 code는 일반
    // 로그인이 아니라 MemberWithdrawalController가 넘겨받아 검증한다.
    public String buildAuthorizationUrl(boolean forceReauth) {
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);

        String state = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        kakaoLoginStateRepository.save(state, nonce);

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(kakao.getProviderDetails().getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", kakao.getClientId())
                .queryParam("redirect_uri", kakao.getRedirectUri())
                .queryParam("scope", "openid")
                .queryParam("state", state)
                .queryParam("nonce", nonce);

        if (forceReauth) {
            builder.queryParam("prompt", "login");
        }

        return builder.build().toUriString();
    }
}
