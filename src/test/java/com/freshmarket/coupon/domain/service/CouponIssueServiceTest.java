package com.freshmarket.coupon.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import com.freshmarket.common.exception.CommonErrorCode;
import com.freshmarket.coupon.domain.cache.CachedCoupon;
import com.freshmarket.coupon.domain.cache.CouponCache;
import com.freshmarket.coupon.domain.dto.CouponIssueResponse;
import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.exception.CouponErrorCode;
import com.freshmarket.coupon.domain.exception.CouponException;
import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.CouponWriteCircuit;
import com.freshmarket.coupon.domain.issue.IssueOutcome;
import com.freshmarket.coupon.domain.issue.IssueTicket;
import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import com.freshmarket.coupon.domain.redis.CouponSeqUnavailableException;
import com.freshmarket.coupon.domain.redis.SeqOutcome;
import com.freshmarket.member.MemberApi;
import com.freshmarket.member.MemberInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class CouponIssueServiceTest {

    private static final long COUPON_ID = 77L;
    private static final long MEMBER_ID = 5001L;
    private static final int TOTAL_QUANTITY = 100;
    private static final long GOLD_GRADE = 3L;

    // 발급 기간 한가운데로 고정한다. 기간 판정을 시계가 흔들지 않게 한다
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 1, 12, 0);

    @Mock
    private CouponCache couponCache;

    @Mock
    private MemberApi memberApi;

    @Mock
    private CouponSeqAllocator allocator;

    @Mock
    private CouponIssueQueue queue;

    @Mock
    private CouponWriteCircuit writeCircuit;

    private CouponIssueService sut;

    @BeforeEach
    void setUp() {
        CouponIssueProperties properties = new CouponIssueProperties(
                Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1, 10_000,
                Duration.ofMillis(100), Duration.ofSeconds(5));
        Clock fixed = Clock.fixed(NOW.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        sut = new CouponIssueService(couponCache, memberApi, allocator, queue, writeCircuit, properties, fixed);
    }

    @Test
    void 없는_쿠폰이면_찾을_수_없다고_답한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .isInstanceOf(CouponException.class)
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.COUPON_NOT_FOUND);
        verifyNoInteractions(allocator, queue);
    }

    @Test
    void 무제한_쿠폰이면_선착순_대상이_아니라고_답한다() {
        // given
        givenCoupon(unlimitedCoupon());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_LIMITED);
        verifyNoInteractions(allocator, queue);
    }

    @Test
    void 발급_스위치가_꺼져_있으면_발급하지_않는다() {
        // given
        givenCoupon(limitedCoupon(false, null, NOW.minusDays(1), NOW.plusDays(1)));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_ISSUABLE);
    }

    @Test
    void 발급_기간이_지났으면_발급하지_않는다() {
        // given
        givenCoupon(limitedCoupon(true, null, NOW.minusDays(2), NOW.minusDays(1)));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_ISSUABLE);
    }

    /*
     * 이 읽기는 DB 왕복이라 선착순 경로에서 피하고 싶은 것이다.
     * 대상 등급이 걸리지 않은 쿠폰에서 회원을 읽지 않는지를 못 박아 둔다.
     */
    @Test
    void 대상_등급이_없으면_회원을_읽지_않는다() {
        // given
        givenCoupon(targetedCoupon(null));
        when(writeCircuit.acceptsWrites()).thenReturn(true);
        when(queue.hasRoom()).thenReturn(true);
        when(allocator.allocate(anyLong(), anyLong(), anyInt())).thenReturn(new SeqOutcome.SoldOut());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.SOLD_OUT);
        verifyNoInteractions(memberApi);
    }

    @Test
    void 대상_등급이_아니면_발급하지_않는다() {
        // given
        givenCoupon(targetedCoupon(GOLD_GRADE));
        when(memberApi.findMember(MEMBER_ID)).thenReturn(Optional.of(memberOfGrade(1L)));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.NOT_TARGET_GRADE);
        verifyNoInteractions(allocator);
    }

    /*
     * memberId 는 검증된 토큰에서 온다. 그런데도 회원이 없다면 그 사이에 탈퇴한 것이라
     * 쿠폰의 실패가 아니라 자격 증명의 실패다.
     */
    @Test
    void 토큰의_회원이_사라졌으면_인증_실패로_답한다() {
        // given
        givenCoupon(targetedCoupon(GOLD_GRADE));
        when(memberApi.findMember(MEMBER_ID)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.UNAUTHENTICATED);
    }

    /*
     * 자리를 순번보다 먼저 본다.
     * 순번을 받고 나서 큐에 못 넣으면 그 번호를 반납해야 하므로, Redis 를 아직 안 부른 것까지 본다.
     */
    /*
     * DB 가 죽어도 Redis 는 멀쩡해 순번 확보 회로는 안 열린다.
     * 이 확인이 없으면 요청마다 번호를 태우고 요청 예산을 다 기다린 뒤에야 실패한다.
     */
    @Test
    void 쓰기_회로가_열려_있으면_순번을_받지_않는다() {
        // given
        givenCoupon(defaultCoupon());
        when(writeCircuit.acceptsWrites()).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verifyNoInteractions(allocator, queue);
    }

    @Test
    void 큐에_자리가_없으면_순번을_받지_않고_혼잡으로_답한다() {
        // given
        givenCoupon(defaultCoupon());
        when(writeCircuit.acceptsWrites()).thenReturn(true);
        when(queue.hasRoom()).thenReturn(false);

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verifyNoInteractions(allocator);
        verify(queue, never()).submit(any());
    }

    @Test
    void 소진이면_최종_실패로_답한다() {
        // given
        givenAllocated(new SeqOutcome.SoldOut());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.SOLD_OUT);
        verify(queue, never()).submit(any());
    }

    // 카운터가 없다. 재고는 남아 있을 수 있으므로 소진이 아니라 혼잡이다
    @Test
    void 준비되지_않았으면_혼잡으로_답한다() {
        // given
        givenAllocated(new SeqOutcome.NotPrepared());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verify(queue, never()).submit(any());
    }

    // 확정 표시가 붙어 있으면 그 자리에서 끝난다. 큐도 DB 도 안 거친다
    @Test
    void 이미_발급된_회원이면_큐를_거치지_않는다() {
        // given
        givenAllocated(new SeqOutcome.AlreadyIssued(6));

        // when
        CouponIssueResponse response = sut.issue(COUPON_ID, MEMBER_ID);

        // then
        assertThat(response).isEqualTo(new CouponIssueResponse(6, true));
        verify(queue, never()).submit(any());
    }

    /*
     * Redis 가 답하지 않거나 회로가 열렸다.
     * 재고는 남아 있을 수 있으므로 소진이 아니고, 큐에도 안 넣는다.
     */
    @Test
    void 쿠폰을_못_읽으면_혼잡으로_답한다() {
        // given
        when(couponCache.find(COUPON_ID)).thenThrow(new QueryTimeoutException("DB 가 답하지 않는다"));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verifyNoInteractions(allocator, queue);
    }

    /*
     * 이 항목의 핵심이다.
     * 고쳐야 할 것까지 "잠시 후 다시" 로 덮으면 그 버그가 재시도에 묻혀 배포 뒤에도 안 드러난다.
     */
    @Test
    void SQL_오류는_혼잡으로_덮지_않는다() {
        // given
        when(couponCache.find(COUPON_ID))
                .thenThrow(new BadSqlGrammarException("발급", "SELECT ...", new java.sql.SQLException()));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .isInstanceOf(BadSqlGrammarException.class);
    }

    @Test
    void 회원을_못_읽으면_혼잡으로_답한다() {
        // given
        givenCoupon(targetedCoupon(GOLD_GRADE));
        when(memberApi.findMember(MEMBER_ID)).thenThrow(new QueryTimeoutException("DB 가 답하지 않는다"));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verifyNoInteractions(allocator);
    }

    // 플러시가 고쳐야 할 실패를 만났다. 혼잡이 아니라 서버 오류로 드러나야 한다
    @Test
    void 플러시가_고칠_실패를_만나면_혼잡으로_답하지_않는다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));
        givenFlushResult(new IssueOutcome.Failed());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 순번을_못_받으면_혼잡으로_답하고_큐에_안_넣는다() {
        // given
        givenCoupon(defaultCoupon());
        when(writeCircuit.acceptsWrites()).thenReturn(true);
        when(queue.hasRoom()).thenReturn(true);
        when(allocator.allocate(COUPON_ID, MEMBER_ID, TOTAL_QUANTITY))
                .thenThrow(new CouponSeqUnavailableException("회로가 열렸다", new IllegalStateException()));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
        verify(queue, never()).submit(any());
    }

    @Test
    void 순번을_받으면_큐에_넣고_발급_결과를_돌려준다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));
        givenFlushResult(new IssueOutcome.Issued(6));

        // when
        CouponIssueResponse response = sut.issue(COUPON_ID, MEMBER_ID);

        // then
        assertThat(response).isEqualTo(new CouponIssueResponse(6, false));
        verify(queue).submit(any(IssueTicket.class));
    }

    // 플러시가 uk_mc_coupon_member 로 갈라낸 경우다. 실패가 아니라 멱등한 성공으로 답한다
    @Test
    void 플러시가_이미_가졌다고_하면_그_순번으로_답한다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));
        givenFlushResult(new IssueOutcome.AlreadyIssued(2));

        // when
        CouponIssueResponse response = sut.issue(COUPON_ID, MEMBER_ID);

        // then
        assertThat(response).isEqualTo(new CouponIssueResponse(2, true));
    }

    @Test
    void 플러시가_못_썼다고_하면_혼잡으로_답한다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));
        givenFlushResult(new IssueOutcome.Congested());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
    }

    /*
     * 요청 예산을 넘겼다. 이 스레드는 떠나지만 그 항목은 큐에 남아 결국 써진다.
     * 사용자가 다시 오면 매핑이 같은 번호를 돌려주므로 번호가 새로 타지 않는다.
     */
    @Test
    void 요청_예산_안에_못_끝내면_혼잡으로_답한다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
    }

    @Test
    void 플러시가_예외로_끝나면_혼잡으로_답한다() {
        // given
        givenAllocated(new SeqOutcome.Allocated(6));
        doAnswer(invocation -> {
            IssueTicket ticket = invocation.getArgument(0);
            ticket.future().completeExceptionally(new IllegalStateException("플러시가 터졌다"));
            return null;
        }).when(queue).submit(any());

        // when, then
        assertThatThrownBy(() -> sut.issue(COUPON_ID, MEMBER_ID))
                .hasFieldOrPropertyWithValue("errorCode", CouponErrorCode.CONGESTED);
    }

    private void givenCoupon(CachedCoupon coupon) {
        when(couponCache.find(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    private void givenAllocated(SeqOutcome outcome) {
        givenCoupon(defaultCoupon());
        when(writeCircuit.acceptsWrites()).thenReturn(true);
        when(queue.hasRoom()).thenReturn(true);
        when(allocator.allocate(COUPON_ID, MEMBER_ID, TOTAL_QUANTITY)).thenReturn(outcome);
    }

    private void givenFlushResult(IssueOutcome outcome) {
        doAnswer(invocation -> {
            IssueTicket ticket = invocation.getArgument(0);
            ticket.complete(outcome);
            return null;
        }).when(queue).submit(any());
    }

    private static MemberInfo memberOfGrade(Long gradeId) {
        return new MemberInfo(MEMBER_ID, "m@example.com", "회원", gradeId, true);
    }

    private static CachedCoupon defaultCoupon() {
        return limitedCoupon(true, null, NOW.minusDays(1), NOW.plusDays(1));
    }

    private static CachedCoupon targetedCoupon(Long targetGradeId) {
        return limitedCoupon(true, targetGradeId, NOW.minusDays(1), NOW.plusDays(1));
    }

    private static CachedCoupon unlimitedCoupon() {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, null, null, null, null, true);
    }

    private static CachedCoupon limitedCoupon(boolean active, Long targetGradeId,
                                              LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
        return new CachedCoupon(COUPON_ID, CouponScope.ORDER, TOTAL_QUANTITY,
                issueStartAt, issueEndAt, targetGradeId, active);
    }
}
