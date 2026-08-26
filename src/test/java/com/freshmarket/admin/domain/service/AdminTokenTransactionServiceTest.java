package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.repository.AdminTokenRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminTokenTransactionServiceTest {

    @Mock
    private AdminTokenRepository adminTokenRepository;

    @Mock
    private Admin admin;

    @Test
    void db의_현재_hash와_만료시간이_유효하면_cas로_회전한다() {
        AdminTokenTransactionService sut = new AdminTokenTransactionService(adminTokenRepository);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(adminTokenRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.isActive()).thenReturn(true);
        when(admin.getId()).thenReturn(1L);
        when(admin.getRole()).thenReturn(AdminRole.ADMIN);
        when(admin.getRefreshTokenHash()).thenReturn("old-hash");
        when(admin.getRefreshTokenExpiresAt()).thenReturn(now.plusHours(1));
        when(adminTokenRepository.compareAndSetRefreshToken(eq(1L), eq("old-hash"), eq("new-hash"), any()))
                .thenReturn(1);

        AdminTokenTransactionService.RotationState state = sut.rotateKnownAdmin(
                1L, "old-hash", "new-hash", now.plusDays(1), now);

        assertThat(state.adminId()).isEqualTo(1L);
        assertThat(state.role()).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void 로그아웃으로_db_hash가_비워진_token은_거부한다() {
        AdminTokenTransactionService sut = new AdminTokenTransactionService(adminTokenRepository);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(adminTokenRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.isActive()).thenReturn(true);
        when(admin.getRefreshTokenHash()).thenReturn(null);

        assertThatThrownBy(() -> sut.rotateKnownAdmin(1L, "old-hash", "new-hash", now.plusDays(1), now))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 만료된_db_token은_거부한다() {
        AdminTokenTransactionService sut = new AdminTokenTransactionService(adminTokenRepository);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(adminTokenRepository.findByRefreshTokenHash("old-hash")).thenReturn(Optional.of(admin));
        when(admin.isActive()).thenReturn(true);
        when(admin.getRefreshTokenHash()).thenReturn("old-hash");
        when(admin.getRefreshTokenExpiresAt()).thenReturn(now.minusSeconds(1));

        assertThatThrownBy(() -> sut.rotateByHash("old-hash", "new-hash", now.plusDays(1), now))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void db_cas가_경합에서_지면_거부한다() {
        AdminTokenTransactionService sut = new AdminTokenTransactionService(adminTokenRepository);
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(adminTokenRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.isActive()).thenReturn(true);
        when(admin.getId()).thenReturn(1L);
        when(admin.getRefreshTokenHash()).thenReturn("old-hash");
        when(admin.getRefreshTokenExpiresAt()).thenReturn(now.plusHours(1));
        when(adminTokenRepository.compareAndSetRefreshToken(eq(1L), eq("old-hash"), eq("new-hash"), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> sut.rotateKnownAdmin(1L, "old-hash", "new-hash", now.plusDays(1), now))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }
}