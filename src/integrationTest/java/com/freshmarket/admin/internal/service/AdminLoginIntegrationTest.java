package com.freshmarket.admin.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminLoginIntegrationTest extends IntegrationTestSupport {

    private static final String LOGIN_ID = "admin.integration";
    private static final String RAW_PASSWORD = "Password!2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminLoginTransactionService adminLoginTransactionService;

    @BeforeEach
    void cleanBefore() {
        deleteTestAdmin();
    }

    @AfterEach
    void cleanAfter() {
        deleteTestAdmin();
    }

    private void deleteTestAdmin() {
        jdbcTemplate.update(
                "DELETE FROM admin WHERE login_id = ?",
                LOGIN_ID);
    }

    /*
     * 이 테스트의 관리 의존성 검증 대상은 MySQL이다.
     * Redis까지 실제로 띄우면 로그인 HTTP 흐름과 DB 쿼리 검증이라는 테스트 목적이 흐려지므로
     * Redis 저장소만 mock으로 격리한다.
     */
    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void 관리자_로그인은_Controller부터_Repository까지_실제_MySQL을_사용한다()
            throws Exception {

        // given
        Long adminId = insertAdmin();

        // when, then
        mockMvc.perform(post("/v1/admin/auth/tokens")
                        .contentType("application/json")
                        .content("""
                                {
                                  "loginId": "%s",
                                  "password": "%s"
                                }
                                """.formatted(LOGIN_ID, RAW_PASSWORD)))
                .andExpect(status().isCreated());

        // Controller → AdminAuthService → AdminLoginTransactionService →
        // AdminRepository → 실제 MySQL까지 흐른 결과 DB에 RT 백업이 저장되어야 한다.
        String refreshTokenHash = jdbcTemplate.queryForObject(
                "SELECT refresh_token_hash "
                        + "FROM admin "
                        + "WHERE admin_id = ?",
                String.class,
                adminId);

        LocalDateTime refreshTokenExpiresAt = jdbcTemplate.queryForObject(
                "SELECT refresh_token_expires_at "
                        + "FROM admin "
                        + "WHERE admin_id = ?",
                LocalDateTime.class,
                adminId);

        assertThat(refreshTokenHash)
                .isNotBlank()
                .hasSize(64);

        assertThat(refreshTokenExpiresAt)
                .isNotNull();

        // DB 저장이 완료된 뒤 Redis 저장까지 이어졌는지 확인한다.
        verify(refreshTokenRepository).save(
                anyString(),
                org.mockito.ArgumentMatchers.eq(adminId),
                org.mockito.ArgumentMatchers.eq("ROLE_ADMIN"),
                org.mockito.ArgumentMatchers.eq(TokenType.ADMIN),
                anyBoolean(),
                any(Duration.class));
    }

    @Test
    void 로그인으로_저장된_RT는_해시가_일치할_때만_조건부로_삭제된다()
            throws Exception {

        // given
        Long adminId = insertAdmin();

        mockMvc.perform(post("/v1/admin/auth/tokens")
                        .contentType("application/json")
                        .content("""
                                {
                                  "loginId": "%s",
                                  "password": "%s"
                                }
                                """.formatted(LOGIN_ID, RAW_PASSWORD)))
                .andExpect(status().isCreated());

        String savedHash = jdbcTemplate.queryForObject(
                "SELECT refresh_token_hash "
                        + "FROM admin "
                        + "WHERE admin_id = ?",
                String.class,
                adminId);

        assertThat(savedHash).isNotBlank();

        // when - 다른 hash로 삭제 시도
        adminLoginTransactionService.clearRefreshTokenIfMatches(adminId, "b".repeat(64));

        // then - hash가 다르므로 기존 RT는 유지된다.
        assertThat(readRefreshTokenHash(adminId)).isEqualTo(savedHash);

        // when - 실제 저장된 hash로 삭제 시도
        adminLoginTransactionService.clearRefreshTokenIfMatches(adminId, savedHash);

        // then - hash가 일치하므로 RT가 제거된다.
        assertThat(readRefreshTokenHash(adminId)).isNull();
        assertThat(readRefreshTokenExpiresAt(adminId)).isNull();
    }

    private Long insertAdmin() {
        String encodedPassword =
                passwordEncoder.encode(RAW_PASSWORD);

        jdbcTemplate.update(
                "INSERT INTO admin "
                        + "(login_id, password_hash, name, role, status, "
                        + "refresh_token_hash, refresh_token_expires_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ADMIN', 'ACTIVE', NULL, NULL, NOW(6), NOW(6))",
                LOGIN_ID,
                encodedPassword,
                "통합테스트 관리자");

        return jdbcTemplate.queryForObject(
                "SELECT admin_id "
                        + "FROM admin "
                        + "WHERE login_id = ?",
                Long.class,
                LOGIN_ID);
    }

    private String readRefreshTokenHash(Long adminId) {
        return jdbcTemplate.queryForObject(
                "SELECT refresh_token_hash "
                        + "FROM admin "
                        + "WHERE admin_id = ?",
                String.class,
                adminId);
    }

    private LocalDateTime readRefreshTokenExpiresAt(Long adminId) {
        return jdbcTemplate.queryForObject(
                "SELECT refresh_token_expires_at "
                        + "FROM admin "
                        + "WHERE admin_id = ?",
                LocalDateTime.class,
                adminId);
    }
}