package com.freshmarket.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Controller -> Service -> Repository -> MySQL까지 관리자 로그아웃 전체 경로와 native/조건부 쿼리를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integrationTest")
class AdminLogoutIntegrationTest extends IntegrationTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AdminLogoutFailureRepository failureRepository;

    // Redis는 관리 의존성(DB) 통합 테스트의 범위 밖이므로 네트워크 경계만 대체한다.
    @MockitoBean RefreshTokenRepository refreshTokenRepository;
    @MockitoBean AccessTokenValidAfterRepository accessTokenValidAfterRepository;

    @Test
    void 관리자_로그인부터_로그아웃까지_Controller_Service_Repository_DB가_연결된다() throws Exception {
        String loginId = "integration.admin.logout";
        long adminId = insertAdmin(loginId, "Password!2026");

        MvcResult login = mockMvc.perform(post("/v1/admin/auth/tokens")
                        .contentType("application/json")
                        .content("""
                                {"loginId":"integration.admin.logout","password":"Password!2026"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String accessToken = cookieValue(login.getResponse().getHeaders(HttpHeaders.SET_COOKIE), "accessToken");
        when(accessTokenValidAfterRepository.isValidAfter(eq("ROLE_ADMIN"), eq(adminId), any()))
                .thenReturn(true);
        when(refreshTokenRepository.findActiveHash("ROLE_ADMIN", adminId)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(delete("/v1/admin/auth/tokens")
                        .cookie(new Cookie("accessToken", accessToken))
                        .with(csrf()))
                .andExpect(status().isNoContent());

        Integer remainingRt = jdbcTemplate.queryForObject(
                "select count(*) from admin where admin_id = ? and refresh_token_hash is not null",
                Integer.class, adminId);
        Integer auditCount = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where admin_id = ? and action = 'ADMIN_LOGOUT'",
                Integer.class, adminId);

        assertThat(remainingRt).isZero();
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void 실패행_upsert_선점_결과반영이_실제_MySQL에서_동작하고_옛_lease는_덮어쓰지_못한다() {
        long adminId = insertAdmin("integration.admin.failure", "Password!2026");
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        String hash = "a".repeat(64);

        failureRepository.upsertFailure(adminId, hash, true, false, now);
        failureRepository.upsertFailure(adminId, null, false, true, now.plusSeconds(1));

        AdminLogoutFailure failure = failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L)
                .stream().filter(row -> row.getAdminId().equals(adminId)).findFirst().orElseThrow();
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isTrue();
        assertThat(failure.getRefreshTokenHash()).isEqualTo(hash);

        LocalDateTime firstClaim = LocalDateTime.of(2026, 8, 26, 12, 1);
        assertThat(failureRepository.claimForRetry(
                failure.getId(), firstClaim, firstClaim.minusMinutes(10))).isEqualTo(1);

        LocalDateTime secondClaim = LocalDateTime.of(2026, 8, 26, 12, 12);
        assertThat(failureRepository.claimForRetry(
                failure.getId(), secondClaim, secondClaim.minusMinutes(10))).isEqualTo(1);

        int staleUpdate = failureRepository.applyOutcomeIfClaimOwned(
                failure.getId(), firstClaim, false, false, true, hash, secondClaim.plusSeconds(1));
        int ownerUpdate = failureRepository.applyOutcomeIfClaimOwned(
                failure.getId(), secondClaim, false, false, true, hash, secondClaim.plusSeconds(2));

        assertThat(staleUpdate).isZero();
        assertThat(ownerUpdate).isEqualTo(1);
        AdminLogoutFailure resolved = failureRepository.findById(failure.getId()).orElseThrow();
        assertThat(resolved.isResolved()).isTrue();
        assertThat(resolved.isProcessing()).isFalse();
    }

    private long insertAdmin(String loginId, String rawPassword) {
        jdbcTemplate.update("""
                insert into admin(login_id, password_hash, name, role, status, created_at, updated_at)
                values (?, ?, '통합테스트 관리자', 'ADMIN', 'ACTIVE', now(6), now(6))
                """, loginId, passwordEncoder.encode(rawPassword));
        return jdbcTemplate.queryForObject(
                "select admin_id from admin where login_id = ?", Long.class, loginId);
    }

    private static String cookieValue(List<String> setCookies, String name) {
        return setCookies.stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                .findFirst()
                .orElseThrow();
    }
}