package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.freshmarket.coupon.internal.entity.CouponScope;
import com.freshmarket.coupon.internal.issue.CouponIssueQueue;
import com.freshmarket.coupon.internal.issue.IssueTicket;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisHashCommands;
import org.springframework.data.redis.connection.RedisZSetCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/*
 * 이 인스턴스가 쥔 티켓을 되살리는 자리를 본다.
 *
 * 계약은 셋이다. 쓰기가 "없을 때만" 인가, 남의 쿠폰을 안 섞는가, 덩이로 나눠 보내는가.
 * 첫째가 멈춤을 없앤 근거다. 덮어쓰기로 되돌아가면 재건이 확정 표시를 미확정으로 덮어 회수가
 * 끝난 번호를 남에게 넘긴다 (docs/coupon/rebuild-v2/design.md 5.2).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("쥔 티켓 되살리기")
class CouponSeqContributorTest {

    private static final long COUPON_ID = 9001L;
    private static final long OTHER_COUPON_ID = 9002L;
    private static final byte[] SEQ_KEY = "coupon:9001:seq".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PENDING_KEY = "coupon:9001:pending".getBytes(StandardCharsets.UTF_8);

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private CouponIssueQueue queue;

    @Mock
    private RedisConnection connection;

    @Mock
    private RedisHashCommands hashCommands;

    @Mock
    private RedisZSetCommands zSetCommands;

    private CouponSeqContributor sut;

    @BeforeEach
    void 준비() {
        CouponSeqRebuildRedis rebuildRedis = mock(CouponSeqRebuildRedis.class);
        when(rebuildRedis.template()).thenReturn(redisTemplate);
        when(connection.hashCommands()).thenReturn(hashCommands);
        when(connection.zSetCommands()).thenReturn(zSetCommands);
        /*
         * 파이프라인 안에서 무엇을 쳤는지 봐야 하므로 콜백을 직접 실행한다.
         * 모의 객체가 콜백을 안 부르면 이 시험이 보려는 명령이 하나도 안 생긴다.
         */
        when(redisTemplate.executePipelined(any(RedisCallback.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, RedisCallback.class).doInRedis(connection);
            return List.of();
        });
        sut = new CouponSeqContributor(rebuildRedis, queue, new SimpleMeterRegistry());
    }

    /*
     * 매핑을 HSETNX 로 쓴다. 이 시험이 이 클래스의 핵심이다.
     *
     * 덮어쓰면 훑는 사이에 커밋된 티켓의 확정 표시("N:1")가 미확정("N")으로 되돌아간다.
     * 그러면 회수가 이미 행이 된 번호를 남에게 넘겨 같은 번호가 두 번 나간다.
     */
    @Test
    void 매핑을_없을_때만_쓴다() {
        given큐에(티켓(9101, 1), 티켓(9102, 2));

        sut.restore(COUPON_ID);

        verify(hashCommands).hSetNX(eq(SEQ_KEY), eq(바이트("9101")), eq(바이트("1")));
        verify(hashCommands).hSetNX(eq(SEQ_KEY), eq(바이트("9102")), eq(바이트("2")));
    }

    /*
     * pending 도 없을 때만 넣는다.
     *
     * 점수는 회수 기준 시각이다. 덮으면 그 회원의 대기가 재건 시각으로 밀려, 회수가 그만큼
     * 늦게 돈다.
     */
    @Test
    void 대기도_없을_때만_넣는다() {
        given큐에(티켓(9101, 1));

        sut.restore(COUPON_ID);

        verify(zSetCommands).zAdd(eq(PENDING_KEY), anyDouble(), eq(바이트("9101")),
                eq(RedisZSetCommands.ZAddArgs.ifNotExists()));
    }

    // 큐는 쿠폰을 가리지 않고 담는다. 남의 이벤트 번호를 섞으면 그쪽 free 가 산 번호를 내준다
    @Test
    void 다른_쿠폰의_티켓은_안_되살린다() {
        given큐에(티켓(9101, 1), 다른_쿠폰_티켓(9102, 7));

        sut.restore(COUPON_ID);

        verify(hashCommands).hSetNX(eq(SEQ_KEY), eq(바이트("9101")), eq(바이트("1")));
        verify(hashCommands, never()).hSetNX(any(), eq(바이트("9102")), any());
    }

    // 되살릴 것이 없으면 Redis 를 안 친다
    @Test
    void 큐가_비었으면_왕복을_안_한다() {
        given큐에();

        sut.restore(COUPON_ID);

        verify(redisTemplate, never()).executePipelined(any(RedisCallback.class));
    }

    /*
     * 덩이로 나눠 보낸다.
     *
     * 만 건을 한 파이프라인에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다.
     * 발급을 멈추지 않겠다고 해놓고 여기서 멈추면 멈춤을 없앤 뜻이 없다.
     */
    @Test
    void 오백_개씩_나눠_보낸다() {
        List<IssueTicket> 많이 = new ArrayList<>();
        for (int seq = 1; seq <= 501; seq++) {
            많이.add(티켓(10_000 + seq, seq));
        }
        when(queue.snapshot()).thenReturn(많이);

        sut.restore(COUPON_ID);

        verify(redisTemplate, times(2)).executePipelined(any(RedisCallback.class));
    }

    // 되살린 시간을 잰다. 안 재면 그 창이 얼마나 긴지 회차 뒤에도 알 수 없다
    @Test
    void 되살린_시간을_잰다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CouponSeqRebuildRedis rebuildRedis = mock(CouponSeqRebuildRedis.class);
        when(rebuildRedis.template()).thenReturn(redisTemplate);
        given큐에(티켓(9101, 1));

        new CouponSeqContributor(rebuildRedis, queue, registry).restore(COUPON_ID);

        assertThat(registry.timer("coupon.seq.rebuild.restore").count()).isEqualTo(1);
    }

    private void given큐에(IssueTicket... 티켓들) {
        when(queue.snapshot()).thenReturn(List.of(티켓들));
    }

    private IssueTicket 티켓(long memberId, int issueSeq) {
        return IssueTicket.of(COUPON_ID, memberId, CouponScope.ORDER, 10_000, issueSeq);
    }

    private IssueTicket 다른_쿠폰_티켓(long memberId, int issueSeq) {
        return IssueTicket.of(OTHER_COUPON_ID, memberId, CouponScope.ORDER, 10_000, issueSeq);
    }

    private byte[] 바이트(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
