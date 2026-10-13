package com.freshmarket.coupon.internal.redis;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.freshmarket.coupon.internal.entity.Coupon;
import com.freshmarket.coupon.internal.issue.CouponIssueProperties;
import com.freshmarket.coupon.internal.repository.CouponRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository;
import com.freshmarket.coupon.internal.repository.MemberCouponSeqRepository.IssuedSeq;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/*
 * 재건이 세우는 값과 그 순서를 본다. 둘이 이 클래스의 계약 전부다.
 *
 * 세는 것이 없는 것도 계약이다. 예전에는 명부를 읽어 참가자 수를 세고 전원이 자기 큐를 올리기를
 * 기다렸는데, 그 세기가 실패하는 경우가 회차마다 새로 나왔다
 * (docs/coupon/rebuild-raise-each-2026-10-14.md 7.1).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("순번 재건")
class CouponSeqRebuilderTest {

    private static final long COUPON_ID = 9001L;
    private static final String COUNTER = "coupon:9001:counter";
    private static final String LOCK = "coupon:9001:rebuild";
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 10, 15, 9, 0);
    private static final int MARGIN = 100;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private CouponRepository couponRepository;

    @Mock
    private MemberCouponSeqRepository seqRepository;

    @Mock
    private CouponSeqInitializer seqInitializer;

    @Mock
    private CouponSeqContributor contributor;

    private CouponSeqMarks marks;
    private CouponSeqRebuilder sut;

    @BeforeEach
    void 준비() {
        CouponSeqRebuildRedis rebuildRedis = mock(CouponSeqRebuildRedis.class);
        when(rebuildRedis.template()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        marks = new CouponSeqMarks();
        sut = new CouponSeqRebuilder(rebuildRedis, couponRepository, seqRepository,
                seqInitializer, contributor, marks, 설정(), new SimpleMeterRegistry());
        given열린_선착순_쿠폰();
        given락을_잡는다(true);
    }

    /*
     * 세울 값이 DB 와 내 기억 중 큰 쪽이다.
     *
     * DB 에는 커밋된 것만 있고 내 기억에는 아직 큐에 있는 번호까지 있다. 낮은 쪽으로 세우면 그
     * 번호들이 다시 나가고 한쪽이 uk_mc_coupon_seq 에 걸려 결번이 된다.
     */
    @Test
    void 기억이_DB_보다_높으면_기억으로_세운다() {
        given발급행(5_887);
        marks.saw(COUPON_ID, 6_050);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("6050"));
    }

    @Test
    void DB_가_기억보다_높으면_DB_로_세운다() {
        given발급행(7_395);
        marks.saw(COUPON_ID, 100);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("7395"));
    }

    /*
     * 커밋된 회원을 먼저 세우고 카운터를 나중에 올린다. 순서가 곧 안전장치다.
     *
     * 카운터를 세우는 것이 곧 문을 여는 것이라, 뒤집히면 이미 번호를 쥔 회원이 seq 에 없는 틈에
     * 재시도해 번호를 하나 더 태운다. 그 행이 uk_mc_coupon_member 에 걸려 결번이 된다.
     */
    @Test
    void 매핑과_큐를_문보다_먼저_세운다() {
        given발급행(10);

        sut.rebuild(COUPON_ID);

        InOrder 순서 = inOrder(hashOperations, contributor, redisTemplate);
        순서.verify(hashOperations).putAll(eq("coupon:9001:seq"), any());
        순서.verify(contributor).restore(COUPON_ID);
        순서.verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));
    }

    /*
     * 커밋된 회원에게 확정 표시를 붙인다.
     *
     * 발급 경로에 DB 중복 검사가 없어 "이미 발급됐다" 를 이 해시에서만 안다. 표시를 안 붙이면
     * 회수가 그 번호를 미확정으로 보고 남에게 넘긴다.
     */
    @Test
    void 커밋된_회원에게_확정_표시를_붙인다() {
        when(seqRepository.findIssuedSeqs(COUPON_ID))
                .thenReturn(List.of(new IssuedSeq(9101, 1), new IssuedSeq(9102, 4)));

        sut.rebuild(COUPON_ID);

        verify(hashOperations).putAll("coupon:9001:seq", Map.of("9101", "1:1", "9102", "4:1"));
    }

    /*
     * 플러시를 멈추지 않는다. 기여자의 쓰기를 "없을 때만" 으로 바꾸어 멈출 이유를 없앴다.
     * 이 시험은 그 계약이 되돌아가지 않게 못박는다.
     */
    @Test
    void 플러시를_멈추지_않는다() {
        given발급행(10);

        sut.rebuild(COUPON_ID);

        // 멈춤이 되살아나면 재건이 플러셔를 알아야 하므로 생성자 인자가 늘어 컴파일이 깨진다
        verify(contributor).restore(COUPON_ID);
    }

    /*
     * 락은 선출에만 쓴다. 못 잡은 대도 카운터와 자기 큐는 세운다.
     *
     * 발급을 막지 않으므로 못 잡은 대가 그냥 돌아가면 그 대의 기억이 카운터에 안 반영된다.
     */
    @Test
    void 락을_못_잡아도_카운터는_올린다() {
        given발급행(10);
        given락을_잡는다(false);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));
        verify(contributor).restore(COUPON_ID);
    }

    // 관리자가 아직 안 연 이벤트도 같은 신호로 온다. 그 둘을 DB 로 가른다
    @Test
    void 열리지_않은_쿠폰은_아무것도_안_한다() {
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.empty());

        sut.rebuild(COUPON_ID);

        verify(contributor, never()).restore(anyLong());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(String.class));
    }

    /*
     * 관리자가 방금 다시 준비한 이벤트는 건드리지 않는다.
     *
     * 준비 단계가 카운터를 0 으로 세운다. 재건이 세울 값은 지난 이벤트의 최댓값이라 덮으면
     * 그만큼의 재고를 통째로 버린다.
     */
    @Test
    void 방금_다시_준비한_이벤트는_덮지_않는다() {
        when(valueOperations.get(COUNTER)).thenReturn("0");

        sut.rebuild(COUPON_ID);

        verify(contributor, never()).restore(anyLong());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(String.class));
    }

    private void given열린_선착순_쿠폰() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.isActive()).thenReturn(true);
        when(coupon.isLimited()).thenReturn(true);
        when(coupon.getIssueEndAt()).thenReturn(END_AT);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }

    private void given락을_잡는다(boolean 잡는다) {
        when(valueOperations.setIfAbsent(eq(LOCK), any(), any(Duration.class))).thenReturn(잡는다);
    }

    private void given발급행(int maxSeq) {
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenReturn(List.of(new IssuedSeq(9101, maxSeq)));
    }

    private CouponIssueProperties 설정() {
        return new CouponIssueProperties(Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1,
                10_000, Duration.ofSeconds(2), true, Duration.ofSeconds(10),
                Duration.ofSeconds(5), MARGIN);
    }
}
