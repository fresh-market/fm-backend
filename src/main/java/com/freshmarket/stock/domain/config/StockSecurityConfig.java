package com.freshmarket.stock.domain.config;

import static org.springframework.http.HttpMethod.GET;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * stock 도메인이 자기 경로의 인가를 소유한다.
 *
 * 소비기한 임박 조회는 URL 이 /v1/products: 아래지만(명세 계약), 실제 로직은
 * stock 도메인 소유다. product 가 아니라 여기서 인가를 소유한다.
 */
@Configuration
class StockSecurityConfig {

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain stockSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher("/v1/products:expiringSoon")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(GET, "/v1/products:expiringSoon").permitAll()
                        .anyRequest().authenticated())
                .build();
    }
}
