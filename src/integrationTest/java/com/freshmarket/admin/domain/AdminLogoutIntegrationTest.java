package com.freshmarket.admin.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.RedisIntegrationTestSupport;
import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Controller -> Service -> Repository -> MySQL/Valkey까지 관리자 로그아웃 전체 경로와 native/Lua/조건부 쿼리를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integrationTest")
class AdminLogoutIntegrationTest extends RedisIntegrationTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AdminLogoutFailureRepository failureRepository;

    @Autowired RefreshTokenRepository refreshTokenRepository;

    @Test
    void 관리자_로그인부터_로그아웃까지_Controller_Service_Repository_MySQL_Valkey가_연결된다() throws Exception {
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
        String refreshTokenHash = refreshTokenRepository.findActiveHash("ROLE_ADMIN", adminId).orElseThrow();
        assertThat(refreshTokenRepository.existsByHash(refreshTokenHash)).isTrue();

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

        // 실제 refresh_token_revoke.lua가 기본 레코드와 현재 active key를 함께 정리했는지 검증한다.
        assertThat(refreshTokenRepository.existsByHash(refreshTokenHash)).isFalse();
        assertThat(refreshTokenRepository.findActiveHash("ROLE_ADMIN", adminId)).isEmpty();

        // logout에서 저장한 실제 Redis cutoff 때문에 같은 Access Token은 더 이상 인증되지 않아야 한다.
        mockMvc.perform(delete("/v1/admin/auth/tokens")
                        .cookie(new Cookie("accessToken", accessToken))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 같은_관리자와_같은_RT해시는_실제_MySQL에서_한_행으로_upsert된다() {
        long adminId = insertAdmin("integration.admin.failure.same-hash", "Password!2026");
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        String hash = "a".repeat(64);

        failureRepository.upsertFailure(adminId, hash, true, false, now);
        failureRepository.upsertFailure(adminId, hash, false, true, now.plusSeconds(1));

        List<AdminLogoutFailure> failures = failureRepository.findAll().stream()
                .filter(row -> row.getAdminId().equals(adminId))
                .toList();

        assertThat(failures).hasSize(1);
        AdminLogoutFailure failure = failures.getFirst();
        assertThat(failure.getRefreshTokenHash()).isEqualTo(hash);
        assertThat(failure.isRedisFailed()).isTrue();
        assertThat(failure.isDbFailed()).isTrue();
    }

    @Test
    void 같은_관리자라도_서로_다른_RT해시는_실제_MySQL에서_별도_행으로_저장된다() {
        long adminId = insertAdmin("integration.admin.failure.different-hash", "Password!2026");
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        String firstHash = "b".repeat(64);
        String secondHash = "c".repeat(64);

        failureRepository.upsertFailure(adminId, firstHash, true, false, now);
        failureRepository.upsertFailure(adminId, secondHash, false, true, now.plusSeconds(1));

        List<AdminLogoutFailure> failures = failureRepository.findAll().stream()
                .filter(row -> row.getAdminId().equals(adminId))
                .toList();

        assertThat(failures).hasSize(2);
        assertThat(failures)
                .extracting(AdminLogoutFailure::getRefreshTokenHash)
                .containsExactlyInAnyOrder(firstHash, secondHash);
    }

    @Test
    void 실패행_선점_결과반영이_실제_MySQL에서_동작하고_옛_lease는_덮어쓰지_못한다() {
        long adminId = insertAdmin("integration.admin.failure.lease", "Password!2026");
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        String hash = "d".repeat(64);

        failureRepository.upsertFailure(adminId, hash, true, false, now);

        AdminLogoutFailure failure = failureRepository.findAll().stream()
                .filter(row -> row.getAdminId().equals(adminId))
                .findFirst()
                .orElseThrow();

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