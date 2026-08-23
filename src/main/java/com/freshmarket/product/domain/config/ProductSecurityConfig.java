package com.freshmarket.product.domain.config;

import static org.springframework.http.HttpMethod.GET;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * product 도메인이 자기 경로의 인가를 소유한다.
 *
 * 관리자 경로(/v1/admin/**)는 이 체인이 잡지 않는다.
 * 어느 도메인도 주장하지 않는 경로는 SecurityConfig 의 마지막 체인이 받아 인증을 요구한다.
 * 그 편이 /v1/admin/products 아래에 다른 도메인(stock)의 경로가 끼어 있는 지금 구조와 맞는다.
 */
@Configuration
class ProductSecurityConfig {

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain productSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                /*
                 * 검색은 콜론 커스텀 메서드(AIP-136)라 /v1/products/** 에 걸리지 않는다.
                 * 콜론 뒤는 경로 구분자가 아니어서 별도 패턴으로 적어야 한다.
                 */
                .securityMatcher("/v1/products", "/v1/products/**", "/v1/products:*")
                .authorizeHttpRequests(auth -> auth
                        // 목록, 상세, 검색은 비로그인도 본다
                        .requestMatchers(GET, "/v1/products", "/v1/products/**", "/v1/products:*").permitAll()

                        .anyRequest().authenticated())
                .build();
    }
}
