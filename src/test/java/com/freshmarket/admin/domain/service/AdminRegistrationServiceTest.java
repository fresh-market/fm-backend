package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.dto.AdminRegistrationRequest;
import com.freshmarket.admin.domain.dto.AdminRegistrationResponse;
import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminRegistrationErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminRegistrationServiceTest {

    private static final Long ISSUER_ADMIN_ID = 1L;
    private static final String SUPER_ADMIN = "ROLE_SUPER_ADMIN";
    private static final String RAW_PASSWORD = "Freshman!2026";

    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final AdminRegistrationService service = new AdminRegistrationService(
            adminRepository, adminAuditLogRepository, passwordEncoder);

    @Test
    void 최고관리자는_관리자_계정을_발급할_수_있다() {
        AdminRegistrationRequest request = request("admin.lee");
        when(adminRepository.findByLoginId(request.loginId())).thenReturn(Optional.empty());
        when(adminRepository.saveAndFlush(any(Admin.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminRegistrationResponse response = service.register(ISSUER_ADMIN_ID, SUPER_ADMIN, request);

        assertThat(response.loginId()).isEqualTo("admin.lee");
        assertThat(response.name()).isEqualTo("이관리");
        assertThat(response.role()).isEqualTo("ADMIN");

        ArgumentCaptor<Admin> adminCaptor = ArgumentCaptor.forClass(Admin.class);
        verify(adminRepository).saveAndFlush(adminCaptor.capture());
        Admin saved = adminCaptor.getValue();
        assertThat(saved.isActive()).isTrue();
        assertThat(passwordEncoder.matches(RAW_PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(saved.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);

        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }

    @Test
    void 일반관리자는_계정을_발급할_수_없다() {
        AdminRegistrationRequest request = request("admin.lee");

        assertThatThrownBy(() -> service.register(ISSUER_ADMIN_ID, "ROLE_ADMIN", request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminRegistrationErrorCode.SUPER_ADMIN_REQUIRED);

        verify(adminRepository, never()).saveAndFlush(any(Admin.class));
    }

    @Test
    void 이미_사용중인_아이디면_계정_발급에_실패한다() {
        AdminRegistrationRequest request = request("admin.lee");
        Admin existing = Admin.register("admin.lee", passwordEncoder.encode(RAW_PASSWORD), "기존관리", AdminRole.ADMIN);
        when(adminRepository.findByLoginId(request.loginId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.register(ISSUER_ADMIN_ID, SUPER_ADMIN, request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminRegistrationErrorCode.LOGIN_ID_DUPLICATED);

        verify(adminRepository, never()).saveAndFlush(any(Admin.class));
    }

    @Test
    void 동시_발급으로_DB_유니크_제약에_걸려도_아이디_중복으로_응답한다() {
        AdminRegistrationRequest request = request("admin.lee");
        when(adminRepository.findByLoginId(request.loginId())).thenReturn(Optional.empty());
        when(adminRepository.saveAndFlush(any(Admin.class)))
                .thenThrow(new DataIntegrityViolationException("uk_admin_login_id"));

        assertThatThrownBy(() -> service.register(ISSUER_ADMIN_ID, SUPER_ADMIN, request))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminRegistrationErrorCode.LOGIN_ID_DUPLICATED);

        verify(adminAuditLogRepository, never()).save(any(AdminAuditLog.class));
    }

    @Test
    void null_요청이면_즉시_예외가_발생한다() {
        assertThatThrownBy(() -> service.register(ISSUER_ADMIN_ID, SUPER_ADMIN, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("request");
    }

    private static AdminRegistrationRequest request(String loginId) {
        return new AdminRegistrationRequest(loginId, RAW_PASSWORD, "이관리", AdminRole.ADMIN);
    }
}