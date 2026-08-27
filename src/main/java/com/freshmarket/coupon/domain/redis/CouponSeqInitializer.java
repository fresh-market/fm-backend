package com.freshmarket.coupon.domain.redis;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 이벤트가 쓰는 네 키를 세우고 치운다. 관리자 API 와 종료 배치만 이 클래스를 부르고, 요청
 * 스레드는 부르지 않는다.
 *
 * <p>{@code docs/coupon/coupon.md} 3장의 "이벤트 종료 후" 가 정한 순서를 지킨다. 특히 배치가
 * 키를 지우는 것이 스위치를 끄고 한참 뒤여야 한다. 카운터가 없는 상태에서 요청이 닿으면
 * 스크립트의 {@code INCR} 이 1 을 주는데, 그 번호는 이미 남이 쓴 것이라
 * {@code uk_mc_coupon_seq} 에 걸린다.
 */
@Component
@RequiredArgsConstructor
public class CouponSeqInitializer {

    // 마감 직전에 들어온 요청이 Redis 에 닿고, 마지막 배치가 실패해 반납하기까지를 덮는 값이다
    private static final long TTL_TAIL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;

    /**
     * 이벤트를 받을 준비를 한다. 카운터가 서야 스크립트가 순번을 내주기 시작한다.
     *
     * <p>이 메서드가 먼저 지우는 것이 중요하다. 지난 이벤트의 매핑이 남아 있으면 스크립트가 그
     * 회원들을 이미 발급받은 것으로 보고 새 이벤트에서 아무것도 안 준다. 종료 배치가 안 돌았을
     * 때 실제로 생긴다.
     *
     * @param issueEndAt 마감 시각. {@code null} 이면 끝나는 시각이 없어 TTL 을 걸 기준이 없다
     */
    public void prepare(long couponId, LocalDateTime issueEndAt) {
        clear(couponId);
        redisTemplate.opsForValue().set(CouponSeqKeys.counter(couponId), "0");
        applyTtl(couponId, issueEndAt);
    }

    /**
     * 카운터에 만료 시각을 다시 건다. 관리자가 발급 시각을 바꾼 뒤에 부른다.
     *
     * <p>이 메서드가 {@code EXPIREAT} 으로 절대 시각을 건다. {@code EXPIRE} 로 상대 초를 걸면
     * 부를 때마다 갱신되어 <b>"마지막 발급 후 1분"</b> 이 되고, 발급이 잠깐 뜸해지면 그때 사라진다.
     *
     * <p>TTL 은 그물일 뿐이다. 주 경로는 종료 배치가 {@link #clear} 로 지우는 것이고, 이것은
     * 그 배치가 안 돌았을 때를 위한 것이다.
     */
    public void applyTtl(long couponId, LocalDateTime issueEndAt) {
        if (issueEndAt == null) {
            return;
        }
        redisTemplate.expireAt(CouponSeqKeys.counter(couponId),
                issueEndAt.plusSeconds(TTL_TAIL_SECONDS).atZone(ZoneId.systemDefault()).toInstant());
    }

    /*
     * 이 메서드는 DEL 이 아니라 UNLINK 를 쓴다.
     * Redis 가 1만 필드 해시를 그 자리에서 해제하지 않고 백그라운드로 넘긴다.
     */
    public void clear(long couponId) {
        redisTemplate.unlink(List.of(
                CouponSeqKeys.seq(couponId),
                CouponSeqKeys.free(couponId),
                CouponSeqKeys.counter(couponId),
                CouponSeqKeys.pending(couponId)));
    }
}
