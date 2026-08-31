package com.freshmarket.admin.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** Controller -> Service -> Repository -> 실제 MySQL까지 관리자 계정 발급 전체 경로를 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminRegistrationIntegrationTest extends IntegrationTestSupport {

    private static final String ISSUER_LOGIN_ID =
            "integration.super-admin.registration";

    private static final String TARGET_LOGIN_ID =
            "integration.admin.registration";

    private static final String RAW_PASSWORD =
            "Freshman!2026";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void cleanBefore() {
        deleteTestData();
    }

    @AfterEach
    void cleanAfter() {
        deleteTestData();
    }

    @Test
    void 최고관리자는_Controller부터_Repository까지_실제_MySQL을_사용해_관리자_계정을_발급한다()
            throws Exception {

        // given
        Long issuerAdminId =
                insertIssuer("SUPER_ADMIN");

        String accessToken =
                jwtTokenProvider.createAccessToken(
                        issuerAdminId,
                        TokenType.ADMIN,
                        "ROLE_SUPER_ADMIN"
                );

        // when, then
        mockMvc.perform(
                        post("/v1/admin/admins")
                                .header(
                                        "Authorization",
                                        "Bearer " + accessToken
                                )
                                .with(csrf())
                                .contentType("application/json")
                                .content("""
                                        {
                                          "loginId": "%s",
                                          "initialPassword": "%s",
                                          "name": "통합테스트 신규관리자",
                                          "role": "ADMIN"
                                        }
                                        """.formatted(
                                        TARGET_LOGIN_ID,
                                        RAW_PASSWORD
                                ))
                )
                .andExpect(status().isCreated())
                .andExpect(
                        jsonPath("$.code")
                                .value("SUCCESS")
                )
                .andExpect(
                        jsonPath("$.data.loginId")
                                .value(TARGET_LOGIN_ID)
                )
                .andExpect(
                        jsonPath("$.data.name")
                                .value("통합테스트 신규관리자")
                )
                .andExpect(
                        jsonPath("$.data.role")
                                .value("ADMIN")
                );

        /*
         * Controller
         * → AdminRegistrationService
         * → AdminRepository
         * → 실제 MySQL
         *
         * 까지 수행된 결과를 DB에서 직접 확인한다.
         */
        Integer createdCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        Integer.class,
                        TARGET_LOGIN_ID
                );

        String passwordHash =
                jdbcTemplate.queryForObject(
                        "SELECT password_hash "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        String.class,
                        TARGET_LOGIN_ID
                );

        String status =
                jdbcTemplate.queryForObject(
                        "SELECT status "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        String.class,
                        TARGET_LOGIN_ID
                );

        String role =
                jdbcTemplate.queryForObject(
                        "SELECT role "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        String.class,
                        TARGET_LOGIN_ID
                );

        String refreshTokenHash =
                jdbcTemplate.queryForObject(
                        "SELECT refresh_token_hash "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        String.class,
                        TARGET_LOGIN_ID
                );

        assertThat(createdCount)
                .isEqualTo(1);

        assertThat(status)
                .isEqualTo("ACTIVE");

        assertThat(role)
                .isEqualTo("ADMIN");

        assertThat(passwordHash)
                .isNotEqualTo(RAW_PASSWORD);

        assertThat(
                passwordEncoder.matches(
                        RAW_PASSWORD,
                        passwordHash
                )
        ).isTrue();

        assertThat(refreshTokenHash)
                .isNull();

        Integer auditCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM audit_log "
                                + "WHERE admin_id = ? "
                                + "AND action = 'ADMIN_ACCOUNT_CREATE' "
                                + "AND target = ?",
                        Integer.class,
                        issuerAdminId,
                        TARGET_LOGIN_ID
                );

        assertThat(auditCount)
                .isEqualTo(1);
    }

    @Test
    void 일반관리자는_관리자_계정을_발급할_수_없다()
            throws Exception {

        // given
        Long issuerAdminId =
                insertIssuer("ADMIN");

        String accessToken =
                jwtTokenProvider.createAccessToken(
                        issuerAdminId,
                        TokenType.ADMIN,
                        "ROLE_ADMIN"
                );

        // when, then
        mockMvc.perform(
                        post("/v1/admin/admins")
                                .header(
                                        "Authorization",
                                        "Bearer " + accessToken
                                )
                                .with(csrf())
                                .contentType("application/json")
                                .content(validRegistrationBody())
                )
                .andExpect(status().isForbidden())
                .andExpect(
                        jsonPath("$.code")
                                .value("ADMIN-005")
                );

        Integer createdCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        Integer.class,
                        TARGET_LOGIN_ID
                );

        assertThat(createdCount)
                .isZero();
    }

    @Test
    void 같은_아이디를_두_번_발급하면_두_번째_요청은_ADMIN_006으로_거부된다()
            throws Exception {

        // given
        Long issuerAdminId =
                insertIssuer("SUPER_ADMIN");

        String accessToken =
                jwtTokenProvider.createAccessToken(
                        issuerAdminId,
                        TokenType.ADMIN,
                        "ROLE_SUPER_ADMIN"
                );

        // when - 첫 번째 발급
        mockMvc.perform(
                        post("/v1/admin/admins")
                                .header(
                                        "Authorization",
                                        "Bearer " + accessToken
                                )
                                .with(csrf())
                                .contentType("application/json")
                                .content(validRegistrationBody())
                )
                .andExpect(status().isCreated());

        // then - 동일한 아이디로 다시 발급
        mockMvc.perform(
                        post("/v1/admin/admins")
                                .header(
                                        "Authorization",
                                        "Bearer " + accessToken
                                )
                                .with(csrf())
                                .contentType("application/json")
                                .content(validRegistrationBody())
                )
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value("ADMIN-006")
                );

        Integer createdCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM admin "
                                + "WHERE login_id = ?",
                        Integer.class,
                        TARGET_LOGIN_ID
                );

        Integer auditCount =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) "
                                + "FROM audit_log "
                                + "WHERE admin_id = ? "
                                + "AND action = 'ADMIN_ACCOUNT_CREATE' "
                                + "AND target = ?",
                        Integer.class,
                        issuerAdminId,
                        TARGET_LOGIN_ID
                );

        assertThat(createdCount)
                .isEqualTo(1);

        assertThat(auditCount)
                .isEqualTo(1);
    }

    private Long insertIssuer(String role) {

        jdbcTemplate.update(
                "INSERT INTO admin "
                        + "(login_id, password_hash, name, role, status, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, '통합테스트 발급자', ?, "
                        + "'ACTIVE', NOW(6), NOW(6))",
                ISSUER_LOGIN_ID,
                passwordEncoder.encode("Issuer!2026"),
                role
        );

        return jdbcTemplate.queryForObject(
                "SELECT admin_id "
                        + "FROM admin "
                        + "WHERE login_id = ?",
                Long.class,
                ISSUER_LOGIN_ID
        );
    }

    private String validRegistrationBody() {
        return """
                {
                  "loginId": "%s",
                  "initialPassword": "%s",
                  "name": "통합테스트 신규관리자",
                  "role": "ADMIN"
                }
                """.formatted(
                TARGET_LOGIN_ID,
                RAW_PASSWORD
        );
    }

    private void deleteTestData() {

        /*
         * audit_log.admin_id가 발급자를 FK로 참조하기 때문에
         * admin보다 audit_log를 먼저 삭제한다.
         */
        jdbcTemplate.update(
                "DELETE FROM audit_log "
                        + "WHERE target = ? "
                        + "OR admin_id IN ("
                        + "SELECT admin_id "
                        + "FROM admin "
                        + "WHERE login_id = ?"
                        + ")",
                TARGET_LOGIN_ID,
                ISSUER_LOGIN_ID
        );

        jdbcTemplate.update(
                "DELETE FROM admin "
                        + "WHERE login_id IN (?, ?)",
                TARGET_LOGIN_ID,
                ISSUER_LOGIN_ID
        );
    }
}