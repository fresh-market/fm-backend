package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminFixture;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminLoginTransactionServiceTest {

    private final AdminRepository adminRepository = mock(AdminRepository.class);
    private final AdminLoginTransactionService sut =
            new AdminLoginTransactionService(adminRepository);

    @Test
    void 활성_관리자면_RefreshToken_백업을_DB_엔티티에_기록한다() {
        Admin admin = AdminFixture.active(
                "admin.kim",
                "encoded-password",
                AdminRole.ADMIN);
        String refreshTokenHash = "a".repeat(64);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 8, 27, 12, 0);

        when(adminRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(admin));

        AdminLoginTransactionService.LoginDbState result =
                sut.issueRefreshToken(1L, refreshTokenHash, expiresAt);

        assertThat(admin.getRefreshTokenHash()).isEqualTo(refreshTokenHash);
        assertThat(admin.getRefreshTokenExpiresAt()).isEqualTo(expiresAt);
        assertThat(result.loginId()).isEqualTo(admin.getLoginId());
        assertThat(result.name()).isEqualTo(admin.getName());
        assertThat(result.role()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void 잠금_조회에서_관리자가_없으면_LOGIN_FAILED가_발생한다() {
        when(adminRepository.findByIdForUpdate(999L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.issueRefreshToken(
                999L,
                "a".repeat(64),
                LocalDateTime.of(2026, 8, 27, 12, 0)))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);
    }

    @Test
    void 잠금_조회후_관리자가_비활성화되어_있으면_LOGIN_FAILED가_발생한다() {
        Admin admin = AdminFixture.inactive(
                "admin.kim",
                "encoded-password",
                AdminRole.ADMIN);

        when(adminRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> sut.issueRefreshToken(
                1L,
                "a".repeat(64),
                LocalDateTime.of(2026, 8, 27, 12, 0)))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminErrorCode.LOGIN_FAILED);

        assertThat(admin.getRefreshTokenHash()).isNull();
        assertThat(admin.getRefreshTokenExpiresAt()).isNull();
    }

    @Test
    void Redis_저장_실패_보상시_같은_RefreshToken_해시만_조건부로_제거한다() {
        String refreshTokenHash = "a".repeat(64);

        sut.clearRefreshTokenIfMatches(1L, refreshTokenHash);

        verify(adminRepository)
                .clearRefreshTokenIfMatches(1L, refreshTokenHash);
    }
}