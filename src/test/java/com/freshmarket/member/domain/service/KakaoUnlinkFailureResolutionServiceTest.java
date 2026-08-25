package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import com.freshmarket.member.domain.exception.MemberException;
import com.freshmarket.member.domain.repository.KakaoUnlinkFailureRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class KakaoUnlinkFailureResolutionServiceTest {

    @Mock
    private KakaoUnlinkFailureRepository failureRepository;

    private KakaoUnlinkFailureResolutionService sut;

    @BeforeEach
    void setUp() {
        sut = new KakaoUnlinkFailureResolutionService(failureRepository);
    }

    @Test
    void 포기_건을_해소_처리한다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        for (int i = 0; i < 4; i++) {
            failure.markRetryFailed();
        }
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        sut.resolve(1L);

        assertThat(failure.isResolved()).isTrue();
    }

    @Test
    void 미해소_포기_건을_페이지로_조회한다() {
        PageRequest pageable = PageRequest.of(0, 20);
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        for (int i = 0; i < 4; i++) {
            failure.markRetryFailed();
        }
        when(failureRepository.findByAttemptCountGreaterThanEqualAndResolvedFalse(
                KakaoUnlinkFailure.MAX_RETRY_ATTEMPTS, pageable))
                .thenReturn(new PageImpl<>(java.util.List.of(failure), pageable, 1));

        org.springframework.data.domain.Page<KakaoUnlinkFailure> result = sut.getStuckFailures(pageable);

        assertThat(result.getContent()).containsExactly(failure);
    }

    @Test
    void 없는_실패_건은_해소할_수_없다() {
        when(failureRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.resolve(1L)).isInstanceOf(MemberException.class);
    }

    @Test
    void 아직_재시도_중인_건은_해소할_수_없다() {
        KakaoUnlinkFailure failure = KakaoUnlinkFailure.record(1L, "kakao-1");
        when(failureRepository.findById(1L)).thenReturn(Optional.of(failure));

        assertThatThrownBy(() -> sut.resolve(1L)).isInstanceOf(MemberException.class);
        assertThat(failure.isResolved()).isFalse();
    }
}
