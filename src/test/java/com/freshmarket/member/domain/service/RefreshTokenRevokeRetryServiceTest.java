package com.freshmarket.member.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

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

    @Test
    void 동시에_같은_회원이_실패로_기록되면_유니크_위반을_잡고_기존_행에_이어_쓴다() {
        RefreshTokenRevokeFailure existing = newFailure(10L, 1L, "ROLE_USER", "old-hash");
        // 첫 조회는 아직 다른 트랜잭션이 커밋 전이라 없음 → save() 시도 → 유니크 위반.
        // 재조회하면 그 사이 먼저 커밋된 행이 보인다.
        when(failureRepository.findByMemberId(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        doThrow(new DataIntegrityViolationException("duplicate member_id"))
                .when(failureRepository).save(any(RefreshTokenRevokeFailure.class));

        sut.recordFailure(1L, "ROLE_USER", "new-hash");

        assertThat(existing.getRefreshTokenHash()).isEqualTo("new-hash");
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

    @Test
    void 한도를_이미_넘은_행도_계속_재시도한다() {
        // 카카오 unlink와 달리 여기선 shouldGiveUp()이어도 retryAllPending()이 건너뛰지 않는다 —
        // 내부 DB/Redis 정리라 비용이 낮고 멱등해서, 인프라가 회복되면 자연히 성공해 큐에서 빠진다.
        RefreshTokenRevokeFailure failure = newFailure(10L, 1L, "ROLE_USER", "hash-1");
        for (int i = 0; i < 10; i++) {
            failure.markRetryFailed();
        }
        when(failureRepository.findAll()).thenReturn(List.of(failure));

        sut.retryAllPending();

        verify(memberRepository).clearRefreshTokenIfMatches(1L, "hash-1");
        verify(refreshTokenRepository).deleteByHash("hash-1");
        verify(outcomeService).markSucceeded(10L);
    }
}
