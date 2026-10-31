package com.freshmarket.coupon.internal.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final String RAISING = "coupon:9001:raising";
    private static final LocalDateTime END_AT = LocalDateTime.of(2026, 10, 15, 9, 0);
    private static final int MARGIN = 100;
    private static final List<String> FREE_KEYS =
            List.of("coupon:9001:seq", "coupon:9001:free", "coupon:9001:counter");

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
    private CouponSeqRebuildRedis rebuildRedis;
    private CouponSeqRebuilder sut;

    @BeforeEach
    void 준비() {
        rebuildRedis = mock(CouponSeqRebuildRedis.class);
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

    /*
     * B 단계는 주인 없는 번호를 free 에 되돌린다. 이 되돌리기가 결번을 0 으로 지켰다.
     *
     * 빼면 V 회차에서 결번이 42, 20, 3, 1 로 남았고 넣은 회차는 0 이었다
     * (docs/coupon/rebuild-raise-each-2026-10-14.md 7.5).
     */
    @Test
    void 주인_없는_번호를_free_에_되돌린다() {
        given카운터가("1000");
        given발급행들(new IssuedSeq(9101, 1), new IssuedSeq(9102, 5));
        when(redisTemplate.execute(any(RedisScript.class), eq(FREE_KEYS),
                eq("2"), eq("3"), eq("4"))).thenReturn(3L);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).execute(any(RedisScript.class), eq(FREE_KEYS),
                eq("2"), eq("3"), eq("4"));
    }

    /*
     * 카운터에 가까운 번호는 안 되돌린다.
     *
     * 방금 나가 아직 큐에 있는 번호를 되돌리면 살아 있는 티켓의 번호를 남에게 내준다.
     * 카운터는 번호가 나갈 때마다 하나씩 오르므로 그 차이가 "그 뒤로 몇 개가 더 나갔나" 다.
     */
    @Test
    void 카운터에_가까운_번호는_안_되돌린다() {
        given카운터가("50");
        given발급행들(new IssuedSeq(9101, 1), new IssuedSeq(9102, 5));

        sut.rebuild(COUPON_ID);

        verify(redisTemplate, never()).execute(any(RedisScript.class), eq(FREE_KEYS), any());
    }

    /*
     * 카운터를 못 읽으면 아무것도 되돌리지 않는다.
     *
     * 0 으로 보면 나이 조건이 전부 거짓이 되어 후보가 비므로, 못 읽은 것이 과하게 되돌리는
     * 쪽으로 기울지 않는다.
     */
    @Test
    void 카운터를_못_읽으면_아무것도_되돌리지_않는다() {
        given카운터가("열");
        given발급행들(new IssuedSeq(9101, 1), new IssuedSeq(9102, 5));

        sut.rebuild(COUPON_ID);

        verify(redisTemplate, never()).execute(any(RedisScript.class), eq(FREE_KEYS), any());
    }

    // 발급 행이 없으면 세울 매핑도 되돌릴 번호도 없다. 카운터는 내 기억으로 올린다
    @Test
    void 발급행이_없으면_매핑을_안_쓴다() {
        given카운터가("1000");
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenReturn(List.of());
        marks.saw(COUPON_ID, 10);

        sut.rebuild(COUPON_ID);

        verify(hashOperations, never()).putAll(any(String.class), anyMap());
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), eq("10"));
    }

    /*
     * 매핑도 덩이로 나눠 쓴다.
     *
     * 만 건을 한 번에 담으면 그동안 Redis 단일 스레드가 다른 요청을 못 받는다. 발급을 멈추지
     * 않겠다고 해놓고 여기서 멈추면 멈춤을 없앤 뜻이 없다.
     */
    @Test
    void 매핑을_오백_개씩_나눠_쓴다() {
        List<IssuedSeq> 많이 = new ArrayList<>();
        for (int seq = 1; seq <= 501; seq++) {
            많이.add(new IssuedSeq(10_000 + seq, seq));
        }
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenReturn(많이);

        sut.rebuild(COUPON_ID);

        verify(hashOperations, times(2)).putAll(eq("coupon:9001:seq"), anyMap());
    }

    /*
     * 카운터의 남은 시한을 나머지 키에 물려준다.
     *
     * 카운터만 수명을 들고 있어서, 각 대가 카운터만 올리는 경로에서 나머지 셋이 시한 없이
     * 남으면 이벤트가 끝난 뒤에도 Redis 에 쌓인다.
     */
    @Test
    void 카운터의_남은_시한을_나머지_키에_물려준다() {
        given발급행(10);
        when(redisTemplate.getExpire(COUNTER, TimeUnit.MILLISECONDS)).thenReturn(60_000L);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate).expire("coupon:9001:seq", Duration.ofMillis(60_000));
        verify(redisTemplate).expire("coupon:9001:free", Duration.ofMillis(60_000));
        verify(redisTemplate).expire("coupon:9001:pending", Duration.ofMillis(60_000));
    }

    // 자기 락만 푼다. 남이 새로 잡은 락을 지우면 둘이 같이 free 를 쓴다
    @Test
    void 자기_락은_끝나면_푼다() {
        given카운터가("1000");
        given발급행(10);
        AtomicReference<String> 내_표 = given락의_표를_받아둔다();

        sut.rebuild(COUPON_ID);

        assertThat(내_표.get()).isNotNull();
        verify(redisTemplate).delete(LOCK);
    }

    @Test
    void 남의_락은_안_푼다() {
        given카운터가("1000");
        given발급행(10);
        given락의_표를_받아둔다();
        when(valueOperations.get(LOCK)).thenReturn("남의-표");

        sut.rebuild(COUPON_ID);

        verify(redisTemplate, never()).delete(LOCK);
    }

    /*
     * 문을 DB 읽기보다 먼저 닫는다. 순서가 뒤집히면 그 사이에 들어온 요청이 뒤로 간 카운터로
     * 번호를 받는다. 그것이 2026-10-14 Y-2 의 결번 404 다.
     */
    @Test
    void 문을_DB_읽기보다_먼저_닫는다() {
        given발급행(10);

        sut.rebuild(COUPON_ID);

        InOrder 순서 = inOrder(valueOperations, seqRepository);
        순서.verify(valueOperations).set(eq(RAISING), any(), any(Duration.class));
        순서.verify(seqRepository).findIssuedSeqs(COUPON_ID);
    }

    /*
     * 끝낼 때 한 번 더 닫는다. 시작할 때만 닫으면 재건이 시한보다 길 때 문이 먼저 열린다.
     * 그리고 이 두 번째 닫기가 "마지막으로 끝낸 대 기준" 을 만든다.
     */
    @Test
    void 끝낼_때_문을_다시_닫는다() {
        given발급행(10);

        sut.rebuild(COUPON_ID);

        verify(valueOperations, times(2)).set(RAISING, "1", Duration.ofSeconds(1));
    }

    // 지우지 않는다. 먼저 끝낸 대가 지우면 아직 하는 대의 문까지 열린다
    @Test
    void 문을_지우지_않는다() {
        given발급행(10);

        sut.rebuild(COUPON_ID);

        verify(redisTemplate, never()).delete(RAISING);
    }

    /*
     * 정지 예산을 넘기면 재건을 그만둔다.
     *
     * 명령 단위 타임아웃으로는 이 시간이 안 묶인다. 재건이 Redis 를 서른 번 안팎 치는데
     * 명령마다 상한 아래로 느리기만 하면 아무것도 안 터지면서 총합이 분 단위로 늘고, 그동안
     * 발급이 멈춰 있다 (docs/coupon/rebuild-gate-2026-10-31.md 6장).
     */
    @Test
    void 정지_예산을_넘기면_그만둔다() {
        CouponSeqRebuilder 예산이_짧은 = new CouponSeqRebuilder(rebuildRedis, couponRepository,
                seqRepository, seqInitializer, contributor, marks,
                설정(Duration.ofMillis(1), Duration.ofMillis(2)), new SimpleMeterRegistry());
        // DB 읽기가 예산을 다 쓰게 만든다. 그 다음 단계에서 확인에 걸린다
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenAnswer(invocation -> {
            Thread.sleep(30);
            return List.of(new IssuedSeq(9101, 10));
        });

        assertThatThrownBy(() -> 예산이_짧은.rebuild(COUPON_ID))
                .isInstanceOf(CouponSeqStallBudgetException.class);

        // 예산을 넘겼으니 카운터를 안 올린다. 문은 시한이 연다
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

    private void given카운터가(String raw) {
        when(valueOperations.get(COUNTER)).thenReturn(raw);
    }

    private void given발급행들(IssuedSeq... rows) {
        when(seqRepository.findIssuedSeqs(COUPON_ID)).thenReturn(List.of(rows));
    }

    /*
     * 락의 표는 재건이 안에서 만든다. 그 값을 받아 두지 않으면 자기 락을 푸는지 못 본다.
     */
    private AtomicReference<String> given락의_표를_받아둔다() {
        AtomicReference<String> 표 = new AtomicReference<>();
        when(valueOperations.setIfAbsent(eq(LOCK), any(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    표.set(invocation.getArgument(1));
                    return true;
                });
        when(valueOperations.get(LOCK)).thenAnswer(invocation -> 표.get());
        return 표;
    }

    private CouponIssueProperties 설정() {
        return 설정(Duration.ofSeconds(1), Duration.ofSeconds(5));
    }

    private CouponIssueProperties 설정(Duration gateTtl, Duration stallBudget) {
        return new CouponIssueProperties(Duration.ofSeconds(60), Duration.ofMillis(20), 500, 1,
                10_000, Duration.ofSeconds(2), true, Duration.ofSeconds(10),
                Duration.ofSeconds(5), MARGIN,
                gateTtl, stallBudget);
    }
}
