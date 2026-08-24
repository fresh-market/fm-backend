package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkStuckReportServiceTest {

    @Mock
    private KakaoUnlinkFailureRepository failureRepository;

    private KakaoUnlinkStuckReportService sut;

    @BeforeEach
    void setUp() {
        sut = new KakaoUnlinkStuckReportService(failureRepository);
    }

    private static KakaoUnlinkFailure givenUpFailure(Long id, Long memberId) {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(memberId, "kakao-" + memberId);
        for (int i = 0; i < 4; i++) {
            failure.markRetryFailed();
        }
        setId(failure, id);
        return failure;
    }

    private static void setId(KakaoUnlinkFailure failure, Long id) {
        try {
            Field field = failure.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(failure, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 쌓인_행이_없으면_빈_목록을_돌려준다() {
        when(failureRepository.findByAttemptCountGreaterThanEqualAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of());

        List<Long> result = sut.reportStuck();

        assertThat(result).isEmpty();
    }

    @Test
    void 포기_문턱을_넘은_행의_memberId를_모아서_돌려준다() {
        KakaoUnlinkFailure givenUp1 = givenUpFailure(1L, 100L);
        KakaoUnlinkFailure givenUp2 = givenUpFailure(2L, 200L);
        when(failureRepository.findByAttemptCountGreaterThanEqualAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of(givenUp1, givenUp2));

        List<Long> result = sut.reportStuck();

        assertThat(result).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    void 포기_건만_조회하고_findAll은_호출하지_않는다() {
        when(failureRepository.findByAttemptCountGreaterThanEqualAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS)).thenReturn(List.of());

        sut.reportStuck();

        org.mockito.Mockito.verify(failureRepository)
                .findByAttemptCountGreaterThanEqualAndResolvedFalse(KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS);
        org.mockito.Mockito.verify(failureRepository, org.mockito.Mockito.never()).findAll();
    }
}
