package com.freshmarket.member.domain.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.common.auth.jwt.RefreshTokenRepository;
import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import com.freshmarket.member.domain.repository.MemberRepository;
import com.freshmarket.member.domain.repository.RefreshTokenRevokeFailureRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

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
    void 처음_실패한_회원이면_새_행을_만든다() {
        when(failureRepository.findByMemberId(1L)).thenReturn(Optional.empty());

        sut.recordFailure(1L, "ROLE_USER", "hash-1");

        verify(failureRepository).save(any(RefreshTokenRevokeFailure.class));
    }

    @Test
    void 이미_실패_기록이_있으면_해시를_최신값으로_갱신하고_새로_만들지_않는다() {
        RefreshTokenRevokeFailure existing = newFailure(10L, 1L, "ROLE_USER", "old-hash");
        when(failureRepository.findByMemberId(1L)).thenReturn(Optional.of(existing));

        sut.recordFailure(1L, "ROLE_USER", "new-hash");

        verify(failureRepository, never()).save(any());
    }

    // ---- retryAllPending() ----

    @Test
    void db_redis_둘_다_성공하면_성공_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findAll()).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(refreshTokenRepository).deleteByHash("hash-1");
        verify(refreshTokenRepository).deleteActiveKey("ROLE_USER", 1L);
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService, never()).markFailed(any());
    }

    @Test
    void db_정리만_실패해도_실패_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findAll()).thenReturn(List.of(failure));
        doThrow(new DataAccessResourceFailureException("db down"))
                .when(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");

        sut.retryAllPending();

        // redis 쪽은 그대로 시도한다 — 이미 성공했더라도 다시 지우는 건 멱등이라 해롭지 않다
        verify(refreshTokenRepository).deleteByHash("hash-1");
        verify(outcomeService).markFailed(10L);
        verify(outcomeService, never()).markSucceeded(any());
    }

    @Test
    void redis_정리만_실패해도_실패_처리로_넘긴다() {
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        when(failureRepository.findAll()).thenReturn(List.of(failure));
        doThrow(new DataAccessResourceFailureException("redis down"))
                .when(refreshTokenRepository).deleteByHash("hash-1");

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(outcomeService).markFailed(10L);
        verify(outcomeService, never()).markSucceeded(any());
    }

    @Test
    void 미완료_건이_여러개면_전부_처리한다() {
        RefreshTokenRevokeFailure f1 = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        RefreshTokenRevokeFailure f2 = newFailure(11L, 2L, "ROLE_USER", "hash-2");
        when(failureRepository.findAll()).thenReturn(List.of(f1, f2));

        sut.retryAllPending();

        verify(memberRepository, times(2)).clearRefreshTokenIfMatches(any(), any());
        verify(outcomeService).markSucceeded(10L);
        verify(outcomeService).markSucceeded(11L);
    }
}
