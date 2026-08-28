package com.freshmarket.admin.domain.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminAuditLog;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminTokenRepositoryTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Admin admin;

    @Mock
    private Query updateQuery;

    private AdminTokenRepository sut;

    @BeforeEach
    void setUp() {
        sut = new AdminTokenRepository(entityManager);
    }

    @Test
    void refresh_token_hash로_감사용_관리자_id를_조회한다() {
        @SuppressWarnings("unchecked")
        TypedQuery<Long> selectQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(selectQuery);
        when(selectQuery.setParameter("refreshTokenHash", "old-hash")).thenReturn(selectQuery);
        when(selectQuery.getResultStream()).thenReturn(Stream.of(1L));

        assertThat(sut.findAdminIdByRefreshTokenHash("old-hash")).contains(1L);
    }

    @Test
    void db의_현재_hash와_만료시간이_유효하면_cas로_회전하고_성공_감사를_남긴다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(entityManager.find(Admin.class, 1L)).thenReturn(admin);
        stubValidAdmin(now);
        stubSuccessfulUpdate();

        AdminTokenRepository.RotationState state = sut.rotateKnownAdmin(
                1L, "old-hash", "new-hash", now.plusDays(1), now);

        assertThat(state.adminId()).isEqualTo(1L);
        assertThat(state.role()).isEqualTo(AdminRole.ADMIN);
        verify(entityManager).persist(any(AdminAuditLog.class));
    }

    @Test
    void 로그아웃으로_db_hash가_비워진_token은_거부한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        when(entityManager.find(Admin.class, 1L)).thenReturn(admin);
        when(admin.isActive()).thenReturn(true);
        when(admin.getRefreshTokenHash()).thenReturn(null);

        assertThatThrownBy(() -> sut.rotateKnownAdmin(1L, "old-hash", "new-hash", now.plusDays(1), now))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    @Test
    void 만료된_db_token은_거부한다() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);
        @SuppressWarnings("unchecked")
        TypedQuery<Admin> selectQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Admin.class))).thenReturn(selectQuery);
        when(selectQuery.setParameter("oldHash", "old-hash")).thenReturn(selectQuery);
        when(selectQuery.getResultStream()).thenReturn(Stream.of(admin));
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
        LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);

        when(entityManager.find(Admin.class, 1L)).thenReturn(admin);

        when(admin.isActive()).thenReturn(true);
        when(admin.getId()).thenReturn(1L);
        when(admin.getRefreshTokenHash()).thenReturn("old-hash");
        when(admin.getRefreshTokenExpiresAt()).thenReturn(now.plusHours(1));

        when(entityManager.createQuery(anyString())).thenReturn(updateQuery);
        when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
        when(updateQuery.executeUpdate()).thenReturn(0);

        assertThatThrownBy(() ->
                sut.rotateKnownAdmin(
                        1L,
                        "old-hash",
                        "new-hash",
                        now.plusDays(1),
                        now))
                .isInstanceOf(AdminException.class)
                .extracting(e -> ((AdminException) e).getErrorCode())
                .isEqualTo(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    private void stubValidAdmin(LocalDateTime now) {
        when(admin.isActive()).thenReturn(true);
        when(admin.getId()).thenReturn(1L);
        when(admin.getRole()).thenReturn(AdminRole.ADMIN);
        when(admin.getRefreshTokenHash()).thenReturn("old-hash");
        when(admin.getRefreshTokenExpiresAt()).thenReturn(now.plusHours(1));
    }

    private void stubSuccessfulUpdate() {
        when(entityManager.createQuery(anyString())).thenReturn(updateQuery);
        when(updateQuery.setParameter(anyString(), any())).thenReturn(updateQuery);
        when(updateQuery.executeUpdate()).thenReturn(1);
    }
}