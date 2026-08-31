package com.freshmarket.coupon.domain.redis;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.freshmarket.coupon.domain.issue.CouponIssueFlusher;
import com.freshmarket.coupon.domain.issue.CouponIssueQueue;
import com.freshmarket.coupon.domain.issue.IssueTicket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 재건이 도는 동안 이 인스턴스가 쥔 순번을 올린다. 주도하든 안 하든 모든 인스턴스가 부른다
 * ({@code docs/coupon/coupon.md} 10장).
 *
 * <p><b>큐는 Redis 가 죽어도 살아 있는 유일한 미확정 기록이다.</b> DB 에는 커밋된 것만 있고,
 * 큐에는 번호를 받았지만 아직 행이 안 된 것이 있다. 이것을 안 올리면 재건이 그 번호들을 아무도
 * 안 쥔 것으로 보고 <b>남에게 다시 내준다.</b>
 *
 * <p><b>남의 큐를 알 필요가 없다.</b> 각자 자기 것만 같은 해시에 올린다. 회원 하나의 티켓은 한
 * 인스턴스에만 있으므로 겹치지 않고, 순서도 상관없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSeqContributor {

    /*
     * 큐를 얼리기를 기다리는 시한이다.
     * 배치 하나가 끝나기를 기다리는 것뿐이라 짧다. 이 시간을 넘기면 그 배치가 막힌 것이므로
     * 훑기를 포기한다. 흔들리는 목록으로 순번의 주인을 정하느니 안 올리는 편이 낫다.
     */
    private static final Duration PAUSE_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redisTemplate;
    private final CouponIssueQueue queue;
    private final CouponIssueFlusher flusher;

    /**
     * 이 인스턴스의 큐에서 이 쿠폰의 티켓을 골라 올린다.
     *
     * <p>플러시를 먼저 멈춘다. 안 멈추면 훑는 사이에 티켓이 DB 로 내려가고, 재건이 그것을
     * 미확정으로 덮어 회수가 확정된 번호를 남에게 넘긴다.
     */
    public void contribute(long couponId) {
        if (!flusher.pause(PAUSE_TIMEOUT)) {
            log.warn("event=COUPON_SEQ_CONTRIBUTE_SKIPPED couponId={} reason=pause-timeout", couponId);
            return;
        }
        try {
            Map<String, String> mine = mineFor(couponId);
            if (mine.isEmpty()) {
                return;
            }
            redisTemplate.opsForHash().putAll(CouponSeqKeys.rebuildQueued(couponId), mine);
            log.warn("event=COUPON_SEQ_CONTRIBUTED couponId={} size={}", couponId, mine.size());
        } finally {
            flusher.resume();
        }
    }

    private Map<String, String> mineFor(long couponId) {
        List<IssueTicket> snapshot = queue.snapshot();
        Map<String, String> mine = new HashMap<>();
        for (IssueTicket ticket : snapshot) {
            if (ticket.couponId() == couponId) {
                mine.put(String.valueOf(ticket.memberId()), String.valueOf(ticket.issueSeq()));
            }
        }
        return mine;
    }
}
