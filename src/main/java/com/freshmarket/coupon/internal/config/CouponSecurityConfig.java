package com.freshmarket.coupon.internal.config;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * coupon 도메인이 자기 경로의 인가를 소유한다.
 *
 * 이 체인이 없으면 기본 체인의 anyRequest().authenticated() 가 관리자 경로까지 받아 준다.
 * 그러면 로그인한 회원 아무나 이벤트를 열고 닫고 발급 시각을 바꿀 수 있다. 이벤트를 여는 것은
 * Redis 카운터를 세우는 일이라, 도는 이벤트에 그것을 다시 걸면 카운터가 0 으로 돌아간다.
 */
@Configuration
class CouponSecurityConfig {

    /*
     * hasRole 이 role 클레임으로 판정한다.
     * 회원 토큰의 role 은 ROLE_USER 라 이 조건을 못 넘으므로, 회원 차단이 이것으로 함께 된다.
     * SUPER_ADMIN 은 RoleHierarchy 가 ADMIN 을 포함시켜 준다.
     */
    private static final String ADMIN_ROLE = "ADMIN";

    private static final String ISSUE_PATH = "/v1/coupons/*/issues";

    /*
     * 관리자 경로를 넓게 잡아도 된다.
     * /v1/admin/coupons 아래에 다른 도메인의 경로가 끼어 있지 않아, 이 체인이 그 아래를 다
     * 삼켜도 경로를 두 도메인이 주장하는 일이 안 생긴다.
     */
    private static final String ADMIN_PATH = "/v1/admin/coupons/**";

    /*
     * 발급분 상태 이력은 쿠폰이 아니라 발급분 자원 아래에 있어 ADMIN_PATH 로는 안 걸린다.
     * 이 경로를 여기 안 더하면 기본 체인(SecurityConfig.defaultFilterChain)의
     * anyRequest().authenticated() 로 떨어져, 로그인한 회원 아무나 남의 쿠폰 이력을 볼 수 있다.
     */
    private static final String ADMIN_MEMBER_COUPON_PATH = "/v1/admin/member-coupons/**";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain couponSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                .securityMatcher(ISSUE_PATH, ADMIN_PATH, ADMIN_MEMBER_COUPON_PATH)
                .authorizeHttpRequests(auth -> auth
                        // 이벤트를 열고 닫고 발급 시각을 바꾸는 것, 발급 이력을 보는 것은 관리자만 한다
                        .requestMatchers(ADMIN_PATH, ADMIN_MEMBER_COUPON_PATH).hasRole(ADMIN_ROLE)
                        // 발급은 로그인한 회원이면 누구나 한다. 자격 판정은 서비스가 따로 본다
                        .anyRequest().authenticated())
                .build();
    }
}
