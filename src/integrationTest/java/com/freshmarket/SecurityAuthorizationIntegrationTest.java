package com.freshmarket;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/*
 * 인가 매트릭스를 고정한다.
 *
 * 필터 체인이 도메인별로 쪼개져 있어(각 도메인 루트의 ~SecurityConfig) 한 도메인의 변경이
 * 다른 경로의 공개 여부를 바꿀 수 있다. 특히 securityMatcher 를 넓히면 그 도메인이 남의
 * 경로까지 삼켜 자기 규칙을 적용한다.
 *
 * 여기서 보는 것은 개별 기능이 아니라 "무엇이 열려 있고 무엇이 막혀 있는가" 다.
 * 도메인에 속하지 않는 검사라 도메인 패키지가 아니라 베이스 패키지에 둔다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAuthorizationIntegrationTest extends IntegrationTestSupport {


    @Autowired
    private MockMvc mockMvc;

    @Test
    void 상품_목록은_비로그인도_연다() throws Exception {
        mockMvc.perform(get("/v1/products"))
                .andExpect(status().isOk());
    }

    /*
     * 이 경로는 state 와 nonce 를 Redis 에 저장하는데 이 테스트는 MySQL 만 띄운다.
     * 그래서 200 이 아니라 500 이 난다. 여기서 볼 것은 기능이 아니라 인가라,
     * "인증을 요구받지 않는다" 만 확인한다.
     */
    @Test
    void 상품_상세는_비로그인도_연다() throws Exception {
        // 없는 상품이라 404 지만, 401 이 아니라는 것이 확인 대상이다
        mockMvc.perform(get("/v1/products/999999"))
                .andExpect(status().isNotFound());
    }

    /*
     * 콜론 커스텀 메서드라 /v1/products/** 패턴에 걸리지 않는다.
     * 체인의 securityMatcher 가 이 형태를 따로 잡지 않으면 조용히 401 이 된다.
     */
    @Test
    void 상품_검색은_비로그인도_연다() throws Exception {
        mockMvc.perform(get("/v1/products:search").param("query", "감귤"))
                .andExpect(status().isOk());
    }

    @Test
    void 로그인_시작은_인증을_요구하지_않는다() throws Exception {
        mockMvc.perform(get("/v1/auth/kakao/authorize"))
                .andExpect(status().is(not(HttpServletResponse.SC_UNAUTHORIZED)));
    }

    @Test
    void API_문서는_비로그인도_연다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }

    @Test
    void 내_정보_조회는_인증을_요구한다() throws Exception {
        mockMvc.perform(get("/v1/members/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그아웃은_인증을_요구한다() throws Exception {
        mockMvc.perform(delete("/v1/auth/tokens"))
                .andExpect(status().isUnauthorized());
    }

    /*
     * 어느 도메인 체인도 주장하지 않는 경로다.
     * 마지막 체인이 받아 막아야 한다. 이것이 무너지면 도메인 체인을 빠뜨린 경로가 열린 채로 뜬다.
     */
    @Test
    void 어느_도메인도_주장하지_않는_경로는_막힌다() throws Exception {
        mockMvc.perform(get("/v1/admin/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 로그인은_비로그인도_연다() throws Exception {
        // 본문이 없어 400 이 나지만, 401 이 아니라는 것이 확인 대상이다
        mockMvc.perform(post("/v1/auth/tokens"))
                .andExpect(status().isBadRequest());
    }
}
