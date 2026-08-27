package com.freshmarket.admin.domain.config;

import static org.springframework.http.HttpMethod.POST;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/*
 * admin 도메인이 자기 경로의 인가를 소유한다 (member/product SecurityConfig와 같은 구조).
 *
 * securityMatcher를 "/v1/admin/**"이 아니라 이 도메인이 소유한 경로로 좁힌 이유:
 * ProductSecurityConfig 주석에 있듯 "/v1/admin/**" 전체를 한 도메인이 갖지 않는다
 * (예: AdminCategoryController는 "/v1/admin/categories"를 쓰지만 product 도메인 소속이다).
 * 이 체인은 관리자 로그인/인증과 카카오 unlink 운영 API만 잡는다.
 *
 * 관리자 로그인은 회원과 달리 DB의 비밀번호를 BCrypt로 검증한다.
 * 요구사항의 "비밀번호 5회 오입력 시 30분 잠금" 정책은 현재 구현 범위에서 제외했으며,
 * 관리자 전용 Rate Limit도 별도 요구사항으로 두지 않았으므로 이번 범위에서는 추가하지 않는다.
 *
 * CSRF: 공통 기본값(ApiSecurityDefaults)은 CSRF를 꺼둔다 — 회원 쪽은 아직 정하지
 * 못한 상태라서다(docs/api/auth.md "정하지 못한 것" 절).
 *
 * admin 로그인은 그 결정 이전에 별도 리뷰를 거쳐 CSRF를 켜기로 이미 확정했다(auth.md "관리자" 절).
 * 그 결정을 지키기 위해 defaults.apply() 이후 이 체인에서만 csrf()를 다시 켠다.
 */
@Configuration
@EnableMethodSecurity
class AdminSecurityConfig {

    private static final String ADMIN = "TYPE_ADMIN";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/admin/auth/**", "/v1/admin/kakao-unlink-failures/**")
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.withDefaults().matcher(
                                        POST, "/v1/admin/auth/tokens"),
                                PathPatternRequestMatcher.withDefaults().matcher(
                                        POST, "/v1/admin/auth/tokens:refresh")))
                .authorizeHttpRequests(auth -> auth
                        // 로그인과 토큰 재발급은 기존 Access Token 인증 없이 호출할 수 있어야 한다
                        .requestMatchers(
                                POST,
                                "/v1/admin/auth/tokens",
                                "/v1/admin/auth/tokens:refresh")
                        .permitAll()
                        // 로그아웃(DELETE /tokens)을 포함한 그 외 관리자 인증 API는 TYPE_ADMIN 권한을 요구한다
                        .anyRequest().hasAuthority(ADMIN))
                .build();
    }

    /*
     * 최고관리자는 일반관리자의 권한을 포함한다.
     * 따라서 @PreAuthorize("hasRole('ADMIN')")은 ADMIN과 SUPER_ADMIN 모두 통과하고,
     * @PreAuthorize("hasRole('SUPER_ADMIN')")은 SUPER_ADMIN만 통과한다.
     */
    @Bean
    static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.withDefaultRolePrefix()
                .role("SUPER_ADMIN").implies("ADMIN")
                .build();
    }

    // @PreAuthorize의 hasRole/hasAuthority 평가에도 위 RoleHierarchy를 적용한다.
    @Bean
    static MethodSecurityExpressionHandler methodSecurityExpressionHandler(RoleHierarchy roleHierarchy) {
        DefaultMethodSecurityExpressionHandler expressionHandler = new DefaultMethodSecurityExpressionHandler();
        expressionHandler.setRoleHierarchy(roleHierarchy);
        return expressionHandler;
    }

    // 관리자 비밀번호 해싱 전용. 회원은 카카오에 인증을 위임하므로 비밀번호 자체가 없다
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}