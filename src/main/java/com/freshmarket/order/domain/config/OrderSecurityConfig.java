package com.freshmarket.order.domain.config;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@RequiredArgsConstructor
class OrderSecurityConfig {

    private static final String MEMBER = "TYPE_MEMBER";
    private static final String ORDERS_PATH = "/v1/orders";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain orderSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(ORDERS_PATH, ORDERS_PATH + "/**", ORDERS_PATH + ":*")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ORDERS_PATH, ORDERS_PATH + "/**", ORDERS_PATH + ":*")
                        .hasAuthority(MEMBER)
                        .anyRequest().authenticated())
                .build();
    }
}
