package com.freshmarket.coupon.domain.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.freshmarket.coupon.domain.entity.CouponScope;
import com.freshmarket.coupon.domain.issue.CouponIssueFlusher;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.IssueTicket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 이 인스턴스가 쥔 순번을 올리는 자리를 본다.
 *
 * 세 가지가 이 클래스의 계약이다. 큐를 얼리고 나서 훑는가, 남의 쿠폰을 안 섞는가, 무슨 일이
 * 있어도 다시 흐르게 하는가. 셋 중 하나만 어긋나도 재건이 순번의 주인을 잘못 정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CouponSeqContributorTest {

    private static final long COUPON_ID = 9001L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private CouponIssueQueue queue;

    @Mock
    private CouponIssueFlusher flusher;

    @InjectMocks
    private CouponSeqContributor sut;

    @Test
    void 자기_큐의_순번을_올린다() {
        given큐에(티켓(9101, 1), 티켓(9102, 2));
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnly(
                Map.entry("9101", "1"),
                Map.entry("9102", "2"));
    }

    // 큐는 쿠폰을 가리지 않고 담는다. 남의 이벤트 순번을 섞어 올리면 그쪽 재건이 틀어진다
    @Test
    void 다른_쿠폰의_티켓은_안_올린다() {
        given큐에(티켓(9101, 1), 다른_쿠폰_티켓(9102, 7));
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        assertThat(올린_것()).containsOnlyKeys("9101");
    }

    /*
     * 이 시험이 이 클래스의 핵심이다.
     * 큐를 얼리지 못하면 훑는 사이에 티켓이 DB 로 내려가고, 재건이 확정된 매핑을 미확정으로 덮어
     * 회수가 그 번호를 남에게 넘긴다. 얼리기에 실패하면 올리지 않는 것이 맞다.
     */
    @Test
    void 큐를_못_얼리면_올리지_않는다() {
        when(flusher.pause(any(Duration.class))).thenReturn(false);

        sut.contribute(COUPON_ID);

        verify(redisTemplate, never()).opsForHash();
        verify(queue, never()).snapshot();
    }

    // 올리다 터져도 큐는 다시 흘러야 한다. 안 그러면 이 인스턴스의 발급이 영영 멈춘다
    @Test
    void 올리다_실패해도_큐를_다시_흐르게_한다() {
        given큐에(티켓(9101, 1));
        given플러시가_멈춘다();
        when(redisTemplate.opsForHash()).thenThrow(new IllegalStateException("Redis 가 답하지 않는다"));

        try {
            sut.contribute(COUPON_ID);
        } catch (RuntimeException ignored) {
            // 이 시험이 보는 것은 예외가 아니라 재개다
        }

        verify(flusher).resume();
    }

    // 올릴 것이 없으면 Redis 를 안 친다
    @Test
    void 큐가_비었으면_아무것도_안_올린다() {
        given큐에();
        given플러시가_멈춘다();

        sut.contribute(COUPON_ID);

        verify(hashOperations, never()).putAll(anyString(), any());
        verify(flusher).resume();
    }

    private void given플러시가_멈춘다() {
        when(flusher.pause(any(Duration.class))).thenReturn(true);
    }

    private void given큐에(IssueTicket... tickets) {
        when(queue.snapshot()).thenReturn(List.of(tickets));
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> 올린_것() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(eq("coupon:9001:rebuild:queued"), captor.capture());
        return captor.getValue();
    }

    private static IssueTicket 티켓(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID, memberId, CouponScope.ORDER, 100, issueSeq);
    }

    private static IssueTicket 다른_쿠폰_티켓(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID + 1, memberId, CouponScope.ORDER, 100, issueSeq);
    }
}
