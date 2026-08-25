package com.freshmarket.admin.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import com.freshmarket.admin.domain.repository.AdminLogoutFailureRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AdminLogoutFailureOutcomeServiceTest {

    private final AdminLogoutFailureRepository failureRepository = mock(AdminLogoutFailureRepository.class);

    private final AdminLogoutFailureOutcomeService sut = new AdminLogoutFailureOutcomeService(failureRepository);

    @Test
    void 둘_다_성공하면_resolved로_바뀐다() {
        AdminLogoutFailure failure = AdminLogoutFailure.record(1L, null, false, true);
        ReflectionTestUtils.setField(failure, "id", 10L);
        when(failureRepository.findById(10L)).thenReturn(Optional.of(failure));

        sut.applyOutcome(10L, true, true, "newHash");

        assertThat(failure.isDbFailed()).isFalse();
        assertThat(failure.isRedisFailed()).isFalse();
    }

    @Test
    void 대상_행이_이미_없으면_아무_일도_하지_않는다() {
        when(failureRepository.findById(10L)).thenReturn(Optional.empty());

        sut.applyOutcome(10L, true, true, "newHash");

        // 예외 없이 조용히 끝나야 한다 (findById만 확인)
        assertThat(failureRepository.findById(10L)).isEmpty();
    }
}