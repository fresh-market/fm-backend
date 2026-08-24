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
 * "/v1/admin/**" 전체를 잡지 않는 이유:
 * ProductSecurityConfig 주석에 있듯 "/v1/admin/**" 전체를 한 도메인이 소유하지 않는다
 * (예: AdminCategoryController는 "/v1/admin/categories"를 쓰지만 product 도메인 소속이다).
 *
 * 따라서 이 체인은 admin 도메인이 실제로 소유한 "/v1/admin/auth/**"와
 * 관리자 계정 관리 경로 "/v1/admin/admins/**"만 담당한다.
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
                .securityMatcher("/v1/admin/auth/**", "/v1/admin/admins", "/v1/admin/admins/**")
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers(
                                PathPatternRequestMatcher.withDefaults().matcher(POST, "/v1/admin/auth/tokens")))
                .authorizeHttpRequests(auth -> auth
                        // 로그인과 재발급은 유효한 Access Token이 없어도 진입해야 한다.
                        // 재발급은 Refresh Token 쿠키 자체를 서비스에서 검증하며 CSRF 검사는 그대로 적용한다.
                        .requestMatchers(POST, "/v1/admin/auth/tokens", "/v1/admin/auth/tokens:refresh").permitAll()
                        // 관리자 계정 API와 로그아웃 등 그 외 admin 도메인 API는 관리자 토큰을 요구한다.
                        // 계정 발급의 SUPER_ADMIN 여부는 서비스에서 검사해 ADMIN-005로 응답한다.
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