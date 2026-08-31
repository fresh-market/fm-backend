package com.freshmarket.admin.internal.config;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/*
 * 관리자 계정 리소스(/v1/admin/admins)는 admin 도메인이 소유한다.
 * 로그인/로그아웃/재발급 작업이 AdminSecurityConfig를 동시에 변경 중이므로 계정 발급 체인을
 * 별도 파일로 분리해 병합 충돌을 피한다. 경로가 겹치지 않아 같은 DOMAIN_CHAIN_ORDER를 써도 된다.
 */
@Configuration
class AdminRegistrationSecurityConfig {

    private static final String ADMIN = "TYPE_ADMIN";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain adminRegistrationSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/admin/admins", "/v1/admin/admins/**")
                .csrf(csrf -> csrf.csrfTokenRepository(new CookieCsrfTokenRepository()))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(ADMIN))
                .build();
    }
}