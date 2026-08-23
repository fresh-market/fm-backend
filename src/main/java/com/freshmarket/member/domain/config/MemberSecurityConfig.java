package com.freshmarket.member.domain.config;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * member 도메인이 자기 경로의 인가를 소유한다.
 *
 * 공통 설정이 도메인 경로를 알지 않게 하려는 것이다(DPB-5-03).
 * 부수 효과로 도메인끼리 같은 파일을 고치지 않게 되어 SecurityConfig 충돌이 사라진다.
 *
 * 이 도메인이 무엇을 열고 무엇을 막는지가 한 파일에 모여 있다.
 * 다른 도메인은 이 파일을 볼 일도 고칠 일도 없다.
 */
@Configuration
class MemberSecurityConfig {

    private static final String MEMBER = "TYPE_MEMBER";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain memberSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/auth/**", "/v1/members/**", "/webhook/kakao/**")
                .authorizeHttpRequests(auth -> auth
                        // 카카오가 호출하는 웹훅이라 인증 쿠키 없이 들어온다. 본문 검증은 컨트롤러가 한다
                        .requestMatchers("/webhook/kakao/unlink").permitAll()

                        // 로그인 시작. 아직 토큰이 없는 시점이다
                        .requestMatchers(GET, "/v1/auth/kakao/authorize").permitAll()

                        /*
                         * 로그인과 재발급은 토큰이 없거나 만료된 상태로 오는 요청이라 열어야 한다.
                         * 대신 AuthRateLimitFilter 가 시도 횟수를 제한한다(SEC-6-01, SEC-6-02).
                         */
                        .requestMatchers(POST, "/v1/auth/tokens", "/v1/auth/tokens:refresh").permitAll()

                        // 로그아웃은 자기 토큰을 지우는 것이라 인증이 필요하다
                        .requestMatchers(DELETE, "/v1/auth/tokens").hasAuthority(MEMBER)

                        .requestMatchers("/v1/members/**").hasAuthority(MEMBER)

                        // 이 체인이 잡은 경로 중 위에 없는 것은 막는다. 기본값이 거부다 (SEC-1-04)
                        .anyRequest().authenticated())
                .build();
    }
}
