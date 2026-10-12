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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.internal.entity.Coupon;
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
 * 복구가 세우는 값과 그 순서를 본다. 둘이 이 클래스의 계약 전부다.
 *
 * 세는 것이 없는 것도 계약이다. 예전에는 명부를 읽어 참가자 수를 세고 전원이 자기 큐를 올리기를
 * 기다렸는데, 그 세기가 실패하는 경우가 회차마다 새로 나왔다. 다섯 결함이 모두 "주도자가
 * 참가자를 세는 데 실패한 것" 이었다 (docs/coupon/rebuild-rearchitecture-2026-10-12.md 1장).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("순번 복구")
class CouponSeqRebuilderTest {

    private static final long COUPON_ID = 9001L;
    private static final String COUNTER = "coupon:9001:counter";
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 10, 13, 9, 0);

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
                seqInitializer, contributor, marks, new SimpleMeterRegistry());
        given열린_선착순_쿠폰();
    }

    /*
     * 세울 값이 DB 와 내 기억 중 큰 쪽이다.
     *
     * DB 에는 커밋된 것만 있고 내 기억에는 아직 큐에 있는 번호까지 있다. 낮은 쪽으로 세우면 그
     * 번호들이 다시 나가고 한쪽이 uk_mc_coupon_seq 에 걸려 결번이 된다.
     */
    @Test
    void 기억이_DB_보다_높으면_기억으로_세운다() {
        given발급행_최댓값(5_887);
        marks.saw(COUPON_ID, 6_050);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("6050"));
    }

    // 내가 쥔 것이 없거나 이미 다 커밋됐으면 DB 가 이긴다
    @Test
    void DB_가_기억보다_높으면_DB_로_세운다() {
        given발급행_최댓값(7_395);
        marks.saw(COUPON_ID, 100);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("7395"));
    }

    // 발급 이력도 기억도 없으면 0 이다. 준비 단계가 세우는 값과 같아진다
    @Test
    void 아무것도_없으면_0_으로_세운다() {
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenReturn(List.of());

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("0"));
    }

    /*
     * 내 큐를 카운터보다 먼저 되살린다. 순서가 곧 안전장치다.
     *
     * 카운터를 세우는 것이 곧 문을 여는 것이라, 뒤집히면 이미 번호를 쥔 회원이 seq 에 없는 틈에
     * 재시도해 번호를 하나 더 태운다. 둘 중 하나가 uk_mc_coupon_member 에 걸려 결번이 된다.
     */
    @Test
    void 내_큐를_문보다_먼저_되살린다() {
        given발급행_최댓값(10);

        sut.rebuild(COUPON_ID);

        InOrder 순서 = inOrder(contributor, redisTemplate);
        순서.verify(contributor).restore(COUPON_ID);
        순서.verify(redisTemplate).execute(any(RedisScript.class), anyList(), any(String.class));
    }

    /*
     * 관리자가 방금 다시 준비한 이벤트는 건드리지 않는다.
     *
     * 준비 단계가 카운터를 0 으로 세운다. 복구가 세울 값은 지난 이벤트의 최댓값이라, 덮으면
     * 그만큼의 재고를 통째로 버린다. 만 장짜리에서 5,887 이면 4,113장만 나간다.
     */
    @Test
    void 방금_다시_준비한_이벤트는_덮지_않는다() {
        when(valueOperations.get(COUNTER)).thenReturn("0");

        sut.rebuild(COUPON_ID);

        verify(contributor, never()).restore(anyLong());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(String.class));
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
     * 락을 잡지 않는다. 그것이 이 설계의 요점이다.
     *
     * 카운터가 올리기만 하는 값이라 낮은 값이 높은 값을 덮을 수 없고, 그래서 동시에 돌아도
     * 안전하다. 락이 막던 것은 그 덮어쓰기뿐이었다 (같은 문서 5.2).
     */
    @Test
    void 락을_잡지_않는다() {
        given발급행_최댓값(10);

        sut.rebuild(COUPON_ID);

        verify(valueOperations, never()).setIfAbsent(any(), any(), any());
    }

    /*
     * 커밋된 회원에게 확정 표시를 붙여 쓴다. 이 해시가 "이미 발급됐다" 를 아는 유일한 자리다.
     *
     * 발급 경로에 DB 중복 검사가 없으므로 비어 있으면 이미 받은 회원의 재시도가 번호를 하나 더
     * 태우고, 그 행이 uk_mc_coupon_member 에 걸려 결번이 된다.
     *
     * 표시를 안 붙이면 반대로 깨진다. 회수가 그 번호를 미확정으로 보고 남에게 넘긴다.
     */
    @Test
    void 커밋된_회원에게_확정_표시를_붙인다() {
        when(seqRepository.findIssuedSeqs(COUPON_ID))
                .thenReturn(List.of(new IssuedSeq(9101, 1), new IssuedSeq(9102, 4)));

        sut.rebuild(COUPON_ID);

        verify(hashOperations).putAll(eq("coupon:9001:seq"),
                eq(java.util.Map.of("9101", "1:1", "9102", "4:1")));
    }

    /*
     * free 를 안 세운다. 그것만 전역 시야를 요구하므로 온라인 밖으로 냈다.
     *
     * 1..최댓값에서 DB 와 모든 큐를 빼야 하는데, 내 큐만 빼고 세우면 남이 쥔 번호를 되살려
     * 같은 번호가 두 번 나간다 (docs/coupon/rebuild-rearchitecture-2026-10-12.md 5.4).
     */
    @Test
    void free_를_세우지_않는다() {
        given발급행_최댓값(10);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate, never()).opsForZSet();
    }

    private void given발급행_최댓값(int maxSeq) {
        when(seqRepository.findIssuedSeqs(COUPON_ID))
                .thenReturn(List.of(new IssuedSeq(9101, maxSeq)));
    }

    private void given열린_선착순_쿠폰() {
        Coupon coupon = mock(Coupon.class);
        when(coupon.isActive()).thenReturn(true);
        when(coupon.isLimited()).thenReturn(true);
        when(coupon.getIssueEndAt()).thenReturn(END_AT);
        when(couponRepository.findById(COUPON_ID)).thenReturn(Optional.of(coupon));
    }
}
