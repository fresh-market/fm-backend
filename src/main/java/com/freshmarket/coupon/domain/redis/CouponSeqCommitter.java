package com.freshmarket.coupon.domain.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
@RequiredArgsConstructor
public class CouponSeqCommitter {

    private static final String COMMITTED_SUFFIX = ":1";

    private final StringRedisTemplate redisTemplate;

    /**
     * 커밋이 끝난 회원들에게 확정 표시를 붙이고 미확정 목록(pending)에서 뺀다.
     *
     * <p>이 메서드는 배치 전체를 명령 둘로 끝낸다. 회원 한 명당 왕복이 아니라 <b>배치당 왕복
     * 둘</b>이라, 배치가 커질수록 회원 한 명이 지는 Redis 비용이 줄어든다.
     */
    public void markCommitted(long couponId, Map<Long, Integer> seqByMember) {
        if (seqByMember.isEmpty()) {
            return;
        }
        Map<String, String> fields = new HashMap<>(seqByMember.size());
        seqByMember.forEach((memberId, seq) -> fields.put(String.valueOf(memberId), seq + COMMITTED_SUFFIX));

        try {
            redisTemplate.opsForHash().putAll(CouponSeqKeys.seq(couponId), fields);
            redisTemplate.opsForZSet().remove(CouponSeqKeys.pending(couponId), fields.keySet().toArray());
        } catch (DataAccessException e) {
            /*
             * 이 표시를 못 남겨도 그 회원들의 발급은 이미 끝나 있다.
             * 달라지는 것은 그 회원이 다시 왔을 때 Redis 에서 안 걸러져 DB 까지 간다는 것뿐이고,
             * 거기서 uk_mc_coupon_member 가 막는다. 그 경로는 원래도 있다.
             */
            log.warn("event=COUPON_SEQ_MARK_FAILED couponId={} size={}", couponId, fields.size(), e);
        }
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
     * 이 번호를 남이 쓰고 있어서 못 쓴 경우를 정리한다({@code uk_mc_coupon_seq} 위반).
     *
     * <p><b>이 메서드는 번호를 반납하지 않는다.</b> 남이 쓰고 있는 번호를 반납하면 그것을 또 다른
     * 회원에게 내주게 된다. 매핑만 지워서 이 회원의 다음 요청이 새 번호를 받게 한다.
     */
    public void dropMapping(long couponId, long memberId) {
        String member = String.valueOf(memberId);
        try {
            redisTemplate.opsForHash().delete(CouponSeqKeys.seq(couponId), member);
            redisTemplate.opsForZSet().remove(CouponSeqKeys.pending(couponId), member);
        } catch (DataAccessException e) {
            log.warn("event=COUPON_SEQ_DROP_FAILED couponId={} memberId={}", couponId, memberId, e);
        }
    }
}
