package com.freshmarket.stock.domain.config;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * stock 도메인이 자기 경로의 인가를 소유한다(product/member SecurityConfig와 같은 구조).
 *
 * stock이 실제로 소유한 관리자 경로는 "/v1/admin/products/{productId}"로 시작하지만, 그 앞부분은
 * product 도메인이 소유한 "/v1/admin/products"(정확한 경로)와 겹치지 않는다 — product 쪽은
 * 경로 세그먼트가 더 없는 정확한 매치만 잡고, 여기는 그 뒤에 세그먼트가 더 붙는 경로만 잡는다
 * (ProductSecurityConfig 참고). 전부 관리자 전용이다.
 */
@Configuration
class StockSecurityConfig {

    private static final String ADMIN = "TYPE_ADMIN";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain stockSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults) throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/admin/products/*/options/*/lots", "/v1/admin/products/*/lots")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority(ADMIN))
                .build();
    }
}
