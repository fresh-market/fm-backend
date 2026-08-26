package com.freshmarket.member.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.opaque.RefreshTokenRepository;
import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.repository.RefreshTokenRevokeFailureRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRevokeRetryServiceTest {

    @Mock
    private RefreshTokenRevokeFailureRepository failureRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private RefreshTokenRevokeRetryOutcomeService outcomeService;

    private RefreshTokenRevokeRetryService sut;

    @BeforeEach
    void setUp() {
        sut = new RefreshTokenRevokeRetryService(failureRepository, memberRepository, refreshTokenRepository, outcomeService);
    }

    private static RefreshTokenRevokeFailure newFailure(Long id, Long memberId, String role, String hash) {
        RefreshTokenRevokeFailure failure = RefreshTokenRevokeFailure.record(memberId, role, hash);
        try {
            Field field = failure.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(failure, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return failure;
    }

    // ---- recordFailure() ----

    @Test
    void 처음_실패한_토큰은_upsert로_새_행을_만든다() {
        sut.recordFailure(1L, "ROLE_USER", "hash-1");

        verify(failureRepository).upsertFailure(1L, "ROLE_USER", "hash-1");
    }

    @Test
    void 같은_토큰의_실패가_반복되면_upsert가_시도횟수를_증가시킨다() {
        sut.recordFailure(1L, "ROLE_USER", "hash-1");

        verify(failureRepository).upsertFailure(1L, "ROLE_USER", "hash-1");
    }

    @Test
    void 같은_회원의_다른_토큰_실패는_별도_행으로_남긴다() {
        sut.recordFailure(1L, "ROLE_USER", "hash-2");

        verify(failureRepository).upsertFailure(1L, "ROLE_USER", "hash-2");
    }

    // ---- retryAllPending() ----

    @Test
    void db_redis_둘_다_성공하면_성공_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findByOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(failureRepository).findByOrderByUpdatedAtAscIdAsc(PageRequest.of(0, 100));
        verify(refreshTokenRepository).revokeIfActiveHashMatches("hash-1", "ROLE_USER", 1L);
        verify(outcomeService).markSucceeded(10L, "hash-1");
        verify(outcomeService, never()).markFailed(any(), any());
    }

    @Test
    void db_정리만_실패해도_실패_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findByOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(failure));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");

        sut.retryAllPending();

        // redis 쪽은 그대로 시도한다 — 이미 성공했더라도 다시 지우는 건 멱등이라 해롭지 않다
        verify(refreshTokenRepository).revokeIfActiveHashMatches("hash-1", "ROLE_USER", 1L);
        verify(outcomeService).markFailed(10L, "hash-1");
        verify(outcomeService, never()).markSucceeded(any(), any());
    }

    @Test
    void redis_정리만_실패해도_실패_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findByOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(failure));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).revokeIfActiveHashMatches("hash-1", "ROLE_USER", 1L);

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(outcomeService).markFailed(10L, "hash-1");
        verify(outcomeService, never()).markSucceeded(any(), any());
    }

    @Test
    void 미완료_건이_여러개면_전부_처리한다() {
        RefreshTokenRevokeFailure f1 = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        RefreshTokenRevokeFailure f2 = newFailure(11L, 2L, "ROLE_USER", "hash-2");
        when(failureRepository.findByOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(f1, f2));

        sut.retryAllPending();

        verify(memberRepository, times(2)).clearRefreshTokenIfMatches(any(), any());
        verify(outcomeService).markSucceeded(10L, "hash-1");
        verify(outcomeService).markSucceeded(11L, "hash-2");
    }

    @Test
    void 한도를_이미_넘은_행도_계속_재시도한다() {
        // 카카오 unlink와 달리 여기선 shouldGiveUp()이어도 retryAllPending()이 건너뛰지 않는다 —
        // 내부 DB/Redis 정리라 비용이 낮고 멱등해서, 인프라가 회복되면 자연히 성공해 큐에서 빠진다.
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        for (int i = 0; i < 10; i++) {
            failure.markRetryFailed();
        }
        when(failureRepository.findByOrderByUpdatedAtAscIdAsc(any())).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(refreshTokenRepository).revokeIfActiveHashMatches("hash-1", "ROLE_USER", 1L);
        verify(outcomeService).markSucceeded(10L, "hash-1");
    }
}
