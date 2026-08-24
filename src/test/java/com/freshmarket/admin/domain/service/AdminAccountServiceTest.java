package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminAccountIssueResponse;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

class AdminAccountServiceTest {

    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final AdminAccountService adminAccountService =
            new AdminAccountService(adminRepository, passwordEncoder, adminAuditLogRepository);

    @Test
    void 최고관리자는_임시비밀번호로_관리자_계정을_발급한다() {
        when(adminRepository.existsByLoginId("admin.lee")).thenReturn(false);
        AtomicReference<Admin> savedAdmin = new AtomicReference<>();
        when(adminRepository.saveAndFlush(any(Admin.class))).thenAnswer(invocation -> {
            Admin admin = invocation.getArgument(0);
            ReflectionTestUtils.setField(admin, "id", 2L);
            savedAdmin.set(admin);
            return admin;
        });

        AdminAccountIssueResponse response = adminAccountService.issue(
                1L,
                "ROLE_SUPER_ADMIN",
                "admin.lee",
                "이관리",
                AdminRole.ADMIN);

        assertThat(response.loginId()).isEqualTo("admin.lee");
        assertThat(response.name()).isEqualTo("이관리");
        assertThat(response.role()).isEqualTo(AdminRole.ADMIN);
        assertThat(response.temporaryPassword()).hasSize(16);
        assertThat(response.temporaryPassword()).containsPattern("[A-Z]");
        assertThat(response.temporaryPassword()).containsPattern("[a-z]");
        assertThat(response.temporaryPassword()).containsPattern("[0-9]");
        assertThat(response.temporaryPassword()).containsPattern("[!@#$%^&*]");
        assertThat(savedAdmin.get().isActive()).isTrue();
        assertThat(passwordEncoder.matches(response.temporaryPassword(), savedAdmin.get().getPasswordHash())).isTrue();

        verify(adminRepository).saveAndFlush(any(Admin.class));
        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void 일반관리자는_관리자_계정을_발급할_수_없다() {
        assertThatThrownBy(() -> adminAccountService.issue(
                1L,
                "ROLE_ADMIN",
                "admin.lee",
                "이관리",
                AdminRole.ADMIN))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.SUPER_ADMIN_REQUIRED);

        verify(adminRepository, never()).saveAndFlush(any(Admin.class));
    }

    @Test
    void 인증주체_정보가_없으면_관리자_계정을_발급할_수_없다() {
        assertThatThrownBy(() -> adminAccountService.issue(
                null,
                null,
                "admin.lee",
                "이관리",
                AdminRole.ADMIN))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.SUPER_ADMIN_REQUIRED);
    }

    @Test
    void 이미_사용중인_로그인아이디면_계정발급을_거부한다() {
        when(adminRepository.existsByLoginId("admin.lee")).thenReturn(true);

        assertThatThrownBy(() -> adminAccountService.issue(
                1L,
                "ROLE_SUPER_ADMIN",
                "admin.lee",
                "이관리",
                AdminRole.ADMIN))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_ID_DUPLICATED);

        verify(adminRepository, never()).saveAndFlush(any(Admin.class));
    }

    @Test
    void 동시요청으로_DB_유니크제약이_충돌해도_아이디중복으로_응답한다() {
        when(adminRepository.existsByLoginId("admin.lee")).thenReturn(false);
        when(adminRepository.saveAndFlush(any(Admin.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate login_id"));

        assertThatThrownBy(() -> adminAccountService.issue(
                1L,
                "ROLE_SUPER_ADMIN",
                "admin.lee",
                "이관리",
                AdminRole.ADMIN))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_ID_DUPLICATED);
    }
}