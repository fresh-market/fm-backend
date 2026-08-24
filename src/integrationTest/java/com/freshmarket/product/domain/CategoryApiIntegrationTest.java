package com.freshmarket.product.domain;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 카테고리 목록 조회를 HTTP 요청부터 DB 까지 전체 경로로 검증한다
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CategoryApiIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 비로그인_상태에서도_카테고리_목록을_조회할_수_있다() throws Exception {
        // when, then — V2__seed_category.sql 이 최상위 5종을 이미 심어둔다
        mockMvc.perform(get("/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)));
    }

    @Test
    void 시드된_카테고리_5종이_이름과_함께_응답된다() throws Exception {
        // V2__seed_category.sql 이 심어둔 최상위 5종(수산물/육류/채소/과일/유제품)을 그대로 확인한다
        mockMvc.perform(get("/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(5)))
                .andExpect(jsonPath("$.data[*].name").value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "수산물", "육류", "채소", "과일", "유제품")));
    }
}