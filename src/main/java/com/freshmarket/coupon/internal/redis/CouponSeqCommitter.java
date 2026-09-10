package com.freshmarket.coupon.internal.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
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
    private static final String MARK_SCRIPT_PATH = "redis/scripts/coupon-mark-committed.lua";
    private static final String DROP_SCRIPT_PATH = "redis/scripts/coupon-drop-mapping.lua";
    private static final String REPAIR_SCRIPT_PATH = "redis/scripts/coupon-return-and-repair.lua";

    /** 커밋 뒤 뒷정리 세 경로다. 어느 것이 깨졌는지에 따라 남는 상태가 달라 나눠 센다. */
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
    private final RedisScript<Long> markScript;
    private final RedisScript<Long> dropScript;
    private final RedisScript<Long> repairScript;
    private final Map<Cleanup, Counter> failures;

    /*
     * 이 클래스가 자기 계량기를 직접 등록한다.
     * CouponIssueMetrics 에 두면 internal.redis 가 internal 을 보고, internal 이 internal.issue 를
     * 보고, 그쪽이 다시 internal.redis 를 봐서 패키지 고리가 닫힌다.
     */
    public CouponSeqCommitter(StringRedisTemplate redisTemplate, MeterRegistry registry) {
        this.redisTemplate = redisTemplate;
        this.markScript = load(MARK_SCRIPT_PATH);
        this.dropScript = load(DROP_SCRIPT_PATH);
        this.repairScript = load(REPAIR_SCRIPT_PATH);
        this.failures = registerFailures(registry);
    }

    /*
     * 이 생성자가 스크립트 파일 셋을 기동 때 읽어 둔다.
     * 늦게 읽으면 패키징이 어긋났을 때 이벤트의 첫 커밋에서야 알게 된다. 여기서 읽으면
     * 대신 기동이 실패한다. CouponSeqAllocator 가 같은 이유로 같은 모양을 쓴다.
     */
    private static RedisScript<Long> load(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        try (InputStream in = resource.getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), Long.class);
        } catch (IOException e) {
            throw new IllegalStateException(path + " 를 읽지 못했다", e);
        }
    }

    /*
     * 기동 때 세 경로를 다 만들어 둔다.
     * 처음 실패할 때 만들면 한 번도 안 깨진 경로가 대시보드에 아예 안 보여서,
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
     * <p>이 메서드는 발급 배칭 한 회분 전체를 왕복 <b>하나</b>로 끝낸다. 회원 수와 무관하게 명령이 둘이고,
     * 그 둘을 스크립트 하나에 담아 보낸다.
     *
     * <p><b>왕복 수가 요청 예산에 그대로 든다.</b> 이 메서드는 요청 스레드를 깨우기 전에 돌아서
     * 그 시간이 {@code commit-wait} 안에 들어간다. 순차로 두 번 치면 Redis 타임아웃이 두 번
     * 잡혀 예산 안쪽 합이 예산을 넘는다({@code application-coupon.yml} 의 계층 표).
     *
     * <p><b>파이프라인이 아니라 스크립트인 이유가 있다.</b> Lettuce 는 공유 커넥션으로
     * 파이프라인을 못 해서 스프링이 전용 커넥션을 따로 얻는데, 우리는 커넥션 풀을 안 써서
     * 호출마다 새 연결이 열린다. 재 보니 빈 파이프라인 하나가 4.6밀리초였고 명령 둘을 순차로
     * 치는 것보다 오히려 느렸다({@code CouponSeqMarkCommittedLatencyIntegrationTest}).
     *
     * <p><b>원자성은 덤이고 필요하지는 않다.</b> 반쪽만 반영된 상태가 생겨도 해가 없다. 확정
     * 표시만 남으면 회수가 그 표시를 보고 안 뺏고, 미확정 해제만 되면 아무도 그 번호를 회수
     * 대상으로 보지 않는다.
     */
    public void markCommitted(long couponId, Map<Long, Integer> seqByMember) {
        if (seqByMember.isEmpty()) {
            return;
        }
        Object[] argv = new Object[seqByMember.size() * 2];
        int i = 0;
        for (Map.Entry<Long, Integer> entry : seqByMember.entrySet()) {
            argv[i++] = String.valueOf(entry.getKey());
            argv[i++] = entry.getValue() + COMMITTED_SUFFIX;
        }

        try {
            redisTemplate.execute(markScript,
                    List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.pending(couponId)), argv);
        } catch (DataAccessException e) {
            /*
             * 이 표시를 못 남겨도 그 회원들의 발급은 이미 끝나 있다.
             * 달라지는 것은 그 회원이 다시 왔을 때 Redis 에서 안 걸러져 DB 까지 간다는 것뿐이고,
             * 거기서 uk_mc_coupon_member 가 막는다. 그 경로는 원래도 있다.
             */
            failures.get(Cleanup.MARK).increment();
            log.warn("event=COUPON_SEQ_MARK_FAILED couponId={} size={}", couponId, seqByMember.size(), e);
        }
    }


    /**
     * 이미 이 쿠폰을 갖고 있는 회원들이 들고 온 순번이 안 쓰인 경우를 정리한다
     * ({@code uk_mc_coupon_member} 위반).
     *
     * <p>이번에 받은 번호는 아무도 안 썼으므로 다시 내줄 자리에 담고, 매핑은 그 회원이 원래
     * 갖고 있던 순번으로 고쳐 놓는다. <b>매핑을 지우기만 하면</b> 그 회원의 다음 요청이 또
     * 새 번호를 받아 또 같은 제약에 막히는 일이 되풀이된다.
     *
     * <p>{@link #markCommitted} 와 같이 회원 수와 무관하게 왕복 하나로 끝낸다. 이 경로는
     * Redis 가 매핑을 잃은 뒤에 돌아온 회원이 타는데, <b>그런 회원은 한꺼번에 생긴다.</b>
     */
    public void returnAndRepairs(long couponId, Collection<Repair> repairs) {
        if (repairs.isEmpty()) {
            return;
        }
        Object[] argv = new Object[repairs.size() * 3];
        int i = 0;
        for (Repair repair : repairs) {
            argv[i++] = String.valueOf(repair.memberId());
            argv[i++] = String.valueOf(repair.burnedSeq());
            argv[i++] = repair.actualSeq() + COMMITTED_SUFFIX;
        }

        try {
            redisTemplate.execute(repairScript,
                    List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.pending(couponId),
                            CouponSeqKeys.free(couponId), CouponSeqKeys.counter(couponId)),
                    argv);
        } catch (DataAccessException e) {
            failures.get(Cleanup.REPAIR).increment();
            log.warn("event=COUPON_SEQ_REPAIR_FAILED couponId={} size={}", couponId, repairs.size(), e);
        }
    }

    /**
     * 되돌릴 것 한 건이다.
     *
     * @param burnedSeq 이번에 받았다가 못 쓴 번호
     * @param actualSeq 이 회원이 원래 갖고 있는 순번
     */
    public record Repair(long memberId, int burnedSeq, int actualSeq) {
    }

    /**
     * 이 번호를 남이 쓰고 있어서 못 쓴 회원들을 정리한다({@code uk_mc_coupon_seq} 위반).
     *
     * <p><b>이 메서드는 번호를 반납하지 않는다.</b> 남이 쓰고 있는 번호를 반납하면 그것을 또 다른
     * 회원에게 내주게 된다. 매핑만 지워서 그 회원들의 다음 요청이 새 번호를 받게 한다.
     *
     * <p>{@link #markCommitted} 와 같이 회원 수와 무관하게 명령 둘로 끝낸다. 이 경로는 회수가
     * 잘못 짚었을 때 지나는데, 그 오판을 부른 원인(확정 표시 실패)이 한꺼번에 여러 건을 만들므로
     * <b>이 경로도 한 회분에 여러 건이 몰린다.</b>
     */
    public void dropMappings(long couponId, Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return;
        }
        Object[] members = memberIds.stream().map(String::valueOf).toArray();
        try {
            redisTemplate.execute(dropScript,
                    List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.pending(couponId)), members);
        } catch (DataAccessException e) {
            failures.get(Cleanup.DROP).increment();
            log.warn("event=COUPON_SEQ_DROP_FAILED couponId={} size={}", couponId, members.length, e);
        }
    }
}
