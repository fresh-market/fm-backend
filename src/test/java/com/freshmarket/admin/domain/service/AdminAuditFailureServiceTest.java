package com.freshmarket.admin.domain.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.AdminAuditFailure;
import com.freshmarket.admin.domain.repository.AdminAuditFailureRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminAuditFailureServiceTest {

    private final AdminAuditFailureRepository failureRepository = mock(AdminAuditFailureRepository.class);
    private final AdminAuditFailureRetryTransactionService retryTransactionService =
            mock(AdminAuditFailureRetryTransactionService.class);
    private final AdminAuditFailureService sut =
            new AdminAuditFailureService(failureRepository, retryTransactionService);

    @Test
    void 감사로그_실패를_아웃박스에_기록한다() {
        sut.recordFailure(1L, "ADMIN_LOGOUT", "1", null);

        verify(failureRepository).saveAndFlush(
                org.mockito.ArgumentMatchers.argThat(failure ->
                        failure.getAdminId().equals(1L)
                                && failure.getAction().equals("ADMIN_LOGOUT")
                                && failure.getTarget().equals("1")
                                && !failure.isResolved()));
    }

    @Test
    void 미해결_감사_실패를_재시도한다() {
        AdminAuditFailure failure = AdminAuditFailure.record(1L, "ADMIN_LOGOUT", "1", null);
        ReflectionTestUtils.setField(failure, "id", 10L);
        when(failureRepository.findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(0L))
                .thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(retryTransactionService).retryOne(10L);
    }
}