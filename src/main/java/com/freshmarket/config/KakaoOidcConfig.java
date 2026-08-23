package com.freshmarket.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

// (2026-08-18 12:05) docs/api/auth.md의 프론트 콜백형 로그인 흐름으로 바꾸면서 신설. 예전엔
// Spring Security의 oauth2Login() 필터 체인이 id_token 서명/클레임 검증을 전부 알아서 해줬는데,
// 이제 그 필터를 안 쓰니 검증기를 직접 만들어야 한다. 다만 검증 로직 자체를 손으로 짜지는 않고,
// Spring Security가 원래 쓰던 NimbusJwtDecoder를 필터 체인 밖에서 단독 빈으로 구성해 재사용한다.
//
// client-id/token-uri/jwks-uri 같은 값은 여기서 새로 안 적고 ClientRegistrationRepository
// (application.yml의 spring.security.oauth2.client.* 설정을 스프링이 이미 파싱해둔 것)에서
// 그대로 가져다 쓴다 — 중복 설정을 피하기 위함이다. issuer-uri만 줘도 스프링이 기동 시 카카오의
// OIDC discovery 문서(/.well-known/openid-configuration)를 읽어 token-uri/jwks-uri를
// 자동으로 채워 넣는다.
@Configuration
public class KakaoOidcConfig {

    private static final String REGISTRATION_ID = "kakao";

    // ClientRegistration.ProviderDetails에도 issuer-uri를 꺼내는 방법이 있지만, application.yml에
    // 이미 우리가 적어둔 값(spring.security.oauth2.client.provider.kakao.issuer-uri)을 그대로
    // 주입받는 편이 API 버전에 덜 민감하다.
    @Value("${spring.security.oauth2.client.provider.kakao.issuer-uri}")
    private String kakaoIssuerUri;

    @Bean
    public JwtDecoder kakaoJwtDecoder(ClientRegistrationRepository clientRegistrationRepository) {
        ClientRegistration kakao = clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID);
        String jwkSetUri = kakao.getProviderDetails().getJwkSetUri();
        String clientId = kakao.getClientId();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // iss/exp는 JwtValidators가 기본으로 검증해준다. aud(우리 client-id로 발급된 토큰이 맞는지)는
        // 여기서 따로 추가한다 — 안 하면 다른 앱용으로 발급된 id_token도 통과해버린다.
        // nonce는 클레임 단위 정적 검증기로 표현할 수 없어(요청마다 기대값이 다름) 여기서 안 하고,
        // 호출하는 쪽(카카오 로그인 서비스)이 Redis에 저장해둔 값과 직접 비교한다.
        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(kakaoIssuerUri);
        OAuth2TokenValidator<Jwt> withAudience =
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(clientId));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));

        return decoder;
    }
}
