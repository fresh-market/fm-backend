package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.AdminAuditFailure;
import com.freshmarket.admin.domain.repository.AdminAuditFailureRepository;
import com.freshmarket.admin.domain.repository.AdminAuditLogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminAuditFailureRetryTransactionServiceTest {

    private final AdminAuditFailureRepository failureRepository = mock(AdminAuditFailureRepository.class);
    private final AdminAuditLogRepository auditLogRepository = mock(AdminAuditLogRepository.class);
    private final AdminAuditFailureRetryTransactionService sut =
            new AdminAuditFailureRetryTransactionService(failureRepository, auditLogRepository);

    @Test
    void 미해결_감사실패를_감사로그로_옮기고_해결처리한다() {
        AdminAuditFailure failure = AdminAuditFailure.record(1L, "ADMIN_LOGOUT", "1", null);
        when(failureRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(failure));

        sut.retryOne(10L);

        verify(auditLogRepository).save(argThat(log -> log != null));
        assertThat(failure.isResolved()).isTrue();
        assertThat(failure.getAttemptCount()).isEqualTo(2);
    }
}