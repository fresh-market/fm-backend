package com.freshmarket.admin.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.common.auth.jwt.TokenType;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RefreshTokenData;
import com.freshmarket.common.auth.opaque.RefreshTokenRepository.RotateOutcome;
import com.freshmarket.common.auth.opaque.TokenHasher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/*
 * 관리자 토큰 재발급을 HTTP 요청부터 실제 MySQL DB까지 전체 경로로 검증한다.
 *
 * Controller -> AdminTokenService -> AdminTokenRepository -> MySQL
 *
 * Redis는 이 테스트의 관리 대상이 아니므로 RefreshTokenRepository만 mock으로 격리한다.
 * AdminTokenRepository는 실제 Spring Bean을 사용하여 JPQL, CAS UPDATE, 엔티티 매핑,
 * 성공 감사 로그 저장까지 실제 DB에서 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTokenRepositoryIntegrationTest extends IntegrationTestSupport {

    private static final String OLD_REFRESH_TOKEN =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OLD_HASH = TokenHasher.sha256(OLD_REFRESH_TOKEN);

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @Test
    void controller부터_repository까지_정상_redis_경로로_재발급한다() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Long adminId = insertAdmin(
                "token-it-normal",
                OLD_HASH,
                now.plusHours(1));

        RefreshTokenData adminTokenData =
                new RefreshTokenData(adminId, "ROLE_ADMIN", TokenType.ADMIN, false);

        when(refreshTokenRepository.find(OLD_REFRESH_TOKEN))
                .thenReturn(Optional.of(adminTokenData));
        when(refreshTokenRepository.compareAndRotate(
                eq(OLD_REFRESH_TOKEN),
                anyString(),
                any()))
                .thenReturn(RotateOutcome.success(adminTokenData));

        mockMvc.perform(post("/v1/admin/auth/tokens:refresh")
                        .cookie(new Cookie("refreshToken", OLD_REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800));

        entityManager.flush();
        entityManager.clear();

        String rotatedHash = refreshTokenHashOf(adminId);
        assertThat(rotatedHash)
                .isNotNull()
                .hasSize(64)
                .isNotEqualTo(OLD_HASH);
        assertThat(successAuditCount(adminId)).isEqualTo(1L);
    }

    @Test
    void controller부터_repository까지_redis_유실시_db_fallback으로_재발급한다() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Long adminId = insertAdmin(
                "token-it-fallback",
                OLD_HASH,
                now.plusHours(1));

        // Redis 기본 레코드가 유실된 상황.
        // 서비스는 DB의 refresh_token_hash를 찾아 rotateByHash()로 fallback해야 한다.
        when(refreshTokenRepository.find(OLD_REFRESH_TOKEN))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/admin/auth/tokens:refresh")
                        .cookie(new Cookie("refreshToken", OLD_REFRESH_TOKEN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(1800));

        entityManager.flush();
        entityManager.clear();

        String rotatedHash = refreshTokenHashOf(adminId);
        assertThat(rotatedHash)
                .isNotNull()
                .hasSize(64)
                .isNotEqualTo(OLD_HASH);
        assertThat(successAuditCount(adminId)).isEqualTo(1L);
    }

    private Long insertAdmin(
            String loginId,
            String refreshTokenHash,
            LocalDateTime expiresAt) {

        entityManager.createNativeQuery(
                        """
                        insert into admin
                        (login_id, password_hash, name, role, status,
                         refresh_token_hash, refresh_token_expires_at,
                         created_at, updated_at)
                        values (?1, ?2, ?3, 'ADMIN', 'ACTIVE',
                                ?4, ?5, now(6), now(6))
                        """)
                .setParameter(1, loginId)
                .setParameter(
                        2,
                        "$2a$10$integration.test.password.hash.placeholder")
                .setParameter(3, "토큰통합테스트관리자")
                .setParameter(4, refreshTokenHash)
                .setParameter(5, expiresAt)
                .executeUpdate();

        entityManager.flush();

        return ((Number) entityManager.createNativeQuery(
                        """
                        select admin_id
                        from admin
                        where login_id = ?1
                        """)
                .setParameter(1, loginId)
                .getSingleResult())
                .longValue();
    }

    private String refreshTokenHashOf(Long adminId) {
        return (String) entityManager.createNativeQuery(
                        """
                        select refresh_token_hash
                        from admin
                        where admin_id = ?1
                        """)
                .setParameter(1, adminId)
                .getSingleResult();
    }

    private long successAuditCount(Long adminId) {
        return ((Number) entityManager.createNativeQuery(
                        """
                        select count(*)
                        from audit_log
                        where admin_id = ?1
                          and action = 'ADMIN_TOKEN_REISSUE'
                          and detail = 'result=SUCCESS'
                        """)
                .setParameter(1, adminId)
                .getSingleResult())
                .longValue();
    }
}