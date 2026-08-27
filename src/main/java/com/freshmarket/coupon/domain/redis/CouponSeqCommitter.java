package com.freshmarket.coupon.domain.redis;

import java.util.HashMap;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * DB 에 쓰고 난 뒤의 Redis 뒷정리다. 플러시 스레드만 부른다.
 *
 * <p>{@link CouponSeqAllocator} 와 나눈 기준은 부르는 주체다. 저쪽은 요청 스레드가 순번을 받으려고
 * 부르고 이쪽은 플러시 스레드가 결과를 반영하려고 부른다. 같은 네 키를 만지지만 경로가 다르다.
 *
 * <p>여기의 모든 연산은 실패해도 정확성을 깨지 않는다. 행은 이미 DB 에 있거나 없고, 이 표시는
 * 다음 요청이 DB 까지 안 가게 아껴 주는 것일 뿐이다. 그래서 예외를 밖으로 던지지 않는다.
 * 던지면 이미 발급된 요청에 혼잡으로 답하게 되어 오히려 잘못이 커진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSeqCommitter {

    private static final String COMMITTED_SUFFIX = ":1";

    private final StringRedisTemplate redisTemplate;

    /**
     * 커밋이 끝난 회원들에게 확정 표시를 붙이고 미확정 목록에서 뺀다.
     *
     * <p>배치 전체를 명령 둘로 끝낸다. 건당 왕복이 아니라 배치당 왕복 둘이다.
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
             * 표시를 못 남겨도 발급은 이미 끝났다.
             * 그 회원의 재시도가 DB 로 가서 uk_mc_coupon_member 에 막힐 뿐이고, 그 경로도 이미 있다.
             */
            log.warn("event=COUPON_SEQ_MARK_FAILED couponId={} size={}", couponId, fields.size(), e);
        }
    }

    /**
     * 이 회원이 이미 이 쿠폰을 갖고 있어 이번 순번이 안 쓰인 경우다({@code uk_mc_coupon_member} 위반).
     *
     * <p>이번에 받은 번호는 아무도 안 썼으므로 반납하고, 매핑은 원래 갖고 있던 순번으로 고친다.
     * 지우기만 하면 재요청이 또 새 번호를 받아 또 막히는 것을 되풀이한다.
     *
     * @param burnedSeq 이번에 받았다가 못 쓴 번호
     * @param actualSeq 이 회원이 원래 갖고 있는 순번
     */
    public void returnAndRepair(long couponId, long memberId, int burnedSeq, int actualSeq) {
        String member = String.valueOf(memberId);
        try {
            redisTemplate.opsForZSet().add(CouponSeqKeys.free(couponId), String.valueOf(burnedSeq), burnedSeq);
            redisTemplate.opsForHash().put(CouponSeqKeys.seq(couponId), member, actualSeq + COMMITTED_SUFFIX);
            redisTemplate.opsForZSet().remove(CouponSeqKeys.pending(couponId), member);
        } catch (DataAccessException e) {
            log.warn("event=COUPON_SEQ_REPAIR_FAILED couponId={} memberId={} burnedSeq={}",
                    couponId, memberId, burnedSeq, e);
        }
    }

    /**
     * 이 번호를 남이 쓰고 있어 못 쓴 경우다({@code uk_mc_coupon_seq} 위반).
     *
     * <p>반납하지 않는다. 남의 번호를 반납하면 그것을 또 다른 회원에게 내주게 된다. 매핑만 지워
     * 이 회원의 재시도가 새 번호를 받게 한다.
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
