package com.freshmarket.coupon.internal.redis;

import java.time.Duration;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 플러시 스레드가 DB 에 쓰고 난 뒤 Redis 를 뒷정리하는 자리다. 이 클래스는 플러시 스레드만 부른다.
 *
 * <p>{@link CouponSeqAllocator} 와 이 클래스를 나눈 기준은 <b>부르는 주체</b>다. 저쪽은 요청
 * 스레드가 순번을 받으려고 부르고 이쪽은 플러시 스레드가 결과를 반영하려고 부른다. 같은 네 키를
 * 만지지만 도는 경로가 다르다.
 *
 * <p><b>이 클래스의 연산은 실패해도 정확성을 안 깬다.</b> 행은 이미 DB 에 있거나 없고, 여기서
 * 남기는 표시는 그 회원의 다음 요청이 DB 까지 안 가게 아껴 주는 것일 뿐이다. 그래서 예외를 밖으로
 * 던지지 않는다. 던지면 이미 발급이 끝난 요청에 혼잡으로 답하게 되어 잘못이 오히려 커진다.
 */
@Slf4j
@Component
public class CouponSeqCommitter {

    private static final String COMMITTED_SUFFIX = ":1";
    private static final String CLEANUP_FAILURES = "coupon.seq.cleanup.failures";

    /** 커밋 뒤 뒷정리 세 갈래다. 어느 것이 깨졌는지에 따라 남는 상태가 달라 나눠 센다. */
    public enum Cleanup {

        /** 확정 표시를 붙이지 못했다. 회수가 그 번호를 버려진 것으로 오판할 수 있다. */
        MARK("mark"),
        /** 안 쓰인 번호를 반납하지 못했다. 그 번호가 아무에게도 안 간다. */
        REPAIR("repair"),
        /** 남이 쓰는 번호의 매핑을 못 지웠다. 그 회원의 재시도가 같은 벽에 다시 막힌다. */
        DROP("drop");

        private final String tag;

        Cleanup(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final StringRedisTemplate redisTemplate;
    private final Map<Cleanup, Counter> failures;

    /*
     * 이 클래스가 자기 계량기를 직접 등록한다.
     * CouponIssueMetrics 에 두면 internal.redis 가 internal 을 보고, internal 이 internal.issue 를
     * 보고, 그쪽이 다시 internal.redis 를 봐서 패키지 고리가 닫힌다.
     */
    public CouponSeqCommitter(StringRedisTemplate redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.failures = registerFailures(registry);
    }

    /*
     * 기동 때 세 갈래를 다 만들어 둔다.
     * 처음 실패할 때 만들면 한 번도 안 깨진 갈래가 대시보드에 아예 안 보여서,
     * "실패 0 건" 과 "안 센다" 가 같은 모양이 된다.
     */
    private static Map<Cleanup, Counter> registerFailures(MeterRegistry registry) {
        Map<Cleanup, Counter> counters = new EnumMap<>(Cleanup.class);
        for (Cleanup cleanup : Cleanup.values()) {
            counters.put(cleanup, Counter.builder(CLEANUP_FAILURES)
                    .tag("op", cleanup.tag)
                    .description("DB 커밋 뒤 Redis 뒷정리가 실패한 횟수. 정확성은 안 깨지지만 순번이 샌다")
                    .register(registry));
        }
        return counters;
    }

    /**
     * 커밋이 끝난 회원들에게 확정 표시를 붙이고 미확정 목록(pending)에서 뺀다.
     *
     * <p>이 메서드는 배치 전체를 왕복 <b>하나</b>로 끝낸다. 회원 수와 무관하게 명령이 둘이고,
     * 그 둘을 파이프라인으로 함께 보내 응답도 함께 기다린다.
     *
     * <p><b>왕복 수가 요청 예산에 그대로 든다.</b> 이 메서드는 요청 스레드를 깨우기 전에 돌아서
     * 그 시간이 {@code commit-wait} 안에 들어간다. 순차로 두 번 치면 Redis 타임아웃이 두 번
     * 잡혀 예산 안쪽 합이 예산을 넘는다({@code application-coupon.yml} 의 계층 표).
     *
     * <p><b>원자성은 필요 없다.</b> 보내기만 묶는 것이라 둘 중 하나만 성공한 상태가 여전히
     * 가능한데, 어느 쪽이든 해가 없다. 확정 표시만 남으면 회수가 그 표시를 보고 안 뺏고,
     * 미확정 해제만 되면 아무도 그 번호를 회수 대상으로 보지 않는다.
     */
    public void markCommitted(long couponId, Map<Long, Integer> seqByMember) {
        if (seqByMember.isEmpty()) {
            return;
        }
        Map<byte[], byte[]> fields = new HashMap<>(seqByMember.size());
        byte[][] members = new byte[seqByMember.size()][];
        int i = 0;
        for (Map.Entry<Long, Integer> entry : seqByMember.entrySet()) {
            byte[] member = bytes(String.valueOf(entry.getKey()));
            fields.put(member, bytes(entry.getValue() + COMMITTED_SUFFIX));
            members[i++] = member;
        }

        byte[] seqKey = bytes(CouponSeqKeys.seq(couponId));
        byte[] pendingKey = bytes(CouponSeqKeys.pending(couponId));
        try {
            redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                connection.hashCommands().hMSet(seqKey, fields);
                connection.zSetCommands().zRem(pendingKey, members);
                // 파이프라인 콜백은 null 을 돌려줘야 한다. 값을 돌려주면 스프링이 예외로 끊는다
                return null;
            });
        } catch (DataAccessException e) {
            /*
             * 이 표시를 못 남겨도 그 회원들의 발급은 이미 끝나 있다.
             * 달라지는 것은 그 회원이 다시 왔을 때 Redis 에서 안 걸러져 DB 까지 간다는 것뿐이고,
             * 거기서 uk_mc_coupon_member 가 막는다. 그 경로는 원래도 있다.
             */
            failures.get(Cleanup.MARK).increment();
            log.warn("event=COUPON_SEQ_MARK_FAILED couponId={} size={}", couponId, fields.size(), e);
        }
    }

    /*
     * 파이프라인 콜백은 직렬화된 값을 받는다.
     * 이 템플릿의 직렬화기를 그대로 쓰므로 opsForHash 로 쓴 값과 같은 바이트가 된다.
     */
    private byte[] bytes(String value) {
        return redisTemplate.getStringSerializer().serialize(value);
    }

    /**
     * 이 회원이 이미 이 쿠폰을 갖고 있어서 이번 순번이 안 쓰인 경우를 정리한다
     * ({@code uk_mc_coupon_member} 위반).
     *
     * <p>이번에 받은 번호는 아무도 안 썼으므로 이 메서드가 그 번호를 반납하고, 매핑은 그 회원이
     * 원래 갖고 있던 순번으로 고쳐 놓는다. <b>매핑을 지우기만 하면</b> 그 회원의 다음 요청이 또
     * 새 번호를 받아 또 같은 제약에 막히는 일이 되풀이된다.
     *
     * @param burnedSeq 이번에 받았다가 못 쓴 번호
     * @param actualSeq 이 회원이 원래 갖고 있는 순번
     */
    public void returnAndRepair(long couponId, long memberId, int burnedSeq, int actualSeq) {
        String member = String.valueOf(memberId);
        try {
            redisTemplate.opsForZSet().add(CouponSeqKeys.free(couponId), String.valueOf(burnedSeq), burnedSeq);
            inheritCounterTtl(couponId);
            redisTemplate.opsForHash().put(CouponSeqKeys.seq(couponId), member, actualSeq + COMMITTED_SUFFIX);
            redisTemplate.opsForZSet().remove(CouponSeqKeys.pending(couponId), member);
        } catch (DataAccessException e) {
            failures.get(Cleanup.REPAIR).increment();
            log.warn("event=COUPON_SEQ_REPAIR_FAILED couponId={} memberId={} burnedSeq={}",
                    couponId, memberId, burnedSeq, e);
        }
    }

    /**
     * 방금 만들어졌을지 모르는 {@code free} 키에 나머지 셋과 같은 수명을 물려준다.
     *
     * <p><b>{@code free} 를 만드는 자리는 바로 위의 반납뿐이다.</b> 순번 확보 스크립트는 그 키에서
     * 꺼내 쓰기만 하고 만들지 않는다. 그래서 여기서 안 걸면 그 키에는 만료가 영영 안 붙는다.
     * 이벤트 종료 배치가 지우기는 하지만, 그 배치가 안 돌았을 때 받쳐 주는 TTL 이 넷 중 하나만
     * 비어 있게 된다.
     *
     * <p>이 메서드가 남은 시간을 읽어 상대 시각으로 다시 거는 것은, 절대 만료 시각을 읽는 명령을
     * 스프링이 안 내주기 때문이다. 그만큼 밀리초 단위로 어긋나지만 TTL 꼬리가 1분이라 잴 값이 아니다.
     */
    private void inheritCounterTtl(long couponId) {
        Long remaining = redisTemplate.getExpire(CouponSeqKeys.counter(couponId), TimeUnit.MILLISECONDS);
        if (remaining != null && remaining > 0) {
            redisTemplate.expire(CouponSeqKeys.free(couponId), Duration.ofMillis(remaining));
        }
    }

    /**
     * 이 번호를 남이 쓰고 있어서 못 쓴 회원들을 정리한다({@code uk_mc_coupon_seq} 위반).
     *
     * <p><b>이 메서드는 번호를 반납하지 않는다.</b> 남이 쓰고 있는 번호를 반납하면 그것을 또 다른
     * 회원에게 내주게 된다. 매핑만 지워서 그 회원들의 다음 요청이 새 번호를 받게 한다.
     *
     * <p>{@link #markCommitted} 와 같이 회원 수와 무관하게 명령 둘로 끝낸다. 이 갈래는 회수가
     * 잘못 짚었을 때 오는데, 그 오판을 부른 원인(확정 표시 실패)이 한꺼번에 여러 건을 만들므로
     * <b>이 갈래도 한 배치에 여러 건이 몰린다.</b>
     */
    public void dropMappings(long couponId, Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return;
        }
        Object[] members = memberIds.stream().map(String::valueOf).toArray();
        try {
            redisTemplate.opsForHash().delete(CouponSeqKeys.seq(couponId), members);
            redisTemplate.opsForZSet().remove(CouponSeqKeys.pending(couponId), members);
        } catch (DataAccessException e) {
            failures.get(Cleanup.DROP).increment();
            log.warn("event=COUPON_SEQ_DROP_FAILED couponId={} size={}", couponId, members.length, e);
        }
    }
}
