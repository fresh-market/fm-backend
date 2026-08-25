package com.freshmarket.product.domain.config;

import static org.springframework.http.HttpMethod.GET;

import com.freshmarket.common.auth.ApiSecurityDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/*
 * product 도메인이 자기 경로의 인가를 소유한다. 회원용 경로(/v1/products/**)뿐 아니라, product
 * 도메인이 실제로 소유한 관리자 경로(상품 등록, 카테고리 CRUD)도 이 체인이 함께 잡는다.
 *
 * 관리자 경로는 "/v1/admin/products/**"처럼 넓게 잡지 않고 이 도메인이 실제로 소유한 정확한
 * 경로(/v1/admin/products, /v1/admin/categories, /v1/admin/categories/*)만 명시한다 —
 * "/v1/admin/products/{productId}/options/{optionId}/lots"처럼 그 아래에 다른 도메인(stock)의
 * 경로가 끼어 있어서, 넓게 잡으면 그 경로까지 이 체인이 삼켜 두 도메인이 같은 경로를 주장하게 된다
 * (StockSecurityConfig가 stock이 소유한, product_id 뒤에 세그먼트가 더 붙는 경로를 따로 잡는다).
 */
@Configuration
class ProductSecurityConfig {

    // hasRole()이 role 클레임으로 판정한다(README.md "권한은 role로 판정한다, RBAC"). MEMBER
    // 토큰의 role(ROLE_USER)은 ADMIN을 만족 못 하므로 회원 차단도 이걸로 자연히 함께 된다.
    private static final String ADMIN_ROLE = "ADMIN";

    @Bean
    @Order(ApiSecurityDefaults.DOMAIN_CHAIN_ORDER)
    SecurityFilterChain productSecurityFilterChain(HttpSecurity http, ApiSecurityDefaults defaults)
            throws Exception {
        return defaults.apply(http)
                /*
                 * 검색은 콜론 커스텀 메서드(AIP-136)라 /v1/products/** 에 걸리지 않는다.
                 * 콜론 뒤는 경로 구분자가 아니어서 별도 패턴으로 적어야 한다.
                 *
                 * /v1/categories 는 카테고리(Category)가 product 도메인 소속 리소스라
                 * 여기서 함께 소유한다.
                 */
                .securityMatcher("/v1/products", "/v1/products/**", "/v1/products:*", "/v1/categories",
                        "/v1/admin/products", "/v1/admin/categories", "/v1/admin/categories/*")
                .authorizeHttpRequests(auth -> auth
                        // 목록, 상세, 검색, 카테고리 목록은 비로그인도 본다
                        .requestMatchers(GET,
                                "/v1/products", "/v1/products/**", "/v1/products:*", "/v1/categories")
                        .permitAll()
                        // 상품 등록, 카테고리 CRUD는 관리자만 호출한다(SUPER_ADMIN도 RoleHierarchy로 포함됨)
                        .requestMatchers("/v1/admin/products", "/v1/admin/categories", "/v1/admin/categories/*")
                                .hasRole(ADMIN_ROLE)

                        .anyRequest().authenticated())
                .build();
    }
}
