package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutTransactionServiceTest {

    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final AdminAuditLogRepository adminAuditLogRepository = mock(AdminAuditLogRepository.class);
    private final AdminLogoutTransactionService service =
            new AdminLogoutTransactionService(adminRepository, adminAuditLogRepository);

    @Test
    void DB_리프레시토큰을_폐기하고_Redis_정리에_쓸_기존_해시를_반환한다() {
        Admin admin = AdminFixture.active("admin.kim", "hash", AdminRole.ADMIN);
        ReflectionTestUtils.setField(admin, "id", 1L);
        admin.issueRefreshToken("a".repeat(64), LocalDateTime.now().plusDays(1));
        when(adminRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(admin));

        AdminLogoutTransactionService.LogoutDbState state = service.revokeRefreshToken(1L);

        assertThat(state.refreshTokenHash()).isEqualTo("a".repeat(64));
        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
        assertThat(admin.isActive()).isTrue();
    }

    @Test
    void 존재하지_않는_관리자의_DB_토큰_폐기는_로그인_실패로_처리한다() {
        when(adminRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revokeRefreshToken(999L))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void AccessToken_차단까지_성공한_로그아웃은_감사로그로_기록한다() {
        service.recordSuccess(1L);

        verify(adminAuditLogRepository).save(any(AdminAuditLog.class));
    }
}