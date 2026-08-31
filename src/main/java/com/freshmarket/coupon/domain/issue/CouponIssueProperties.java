package com.freshmarket.coupon.domain.issue;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 선착순 발급의 조율 값을 담는다. {@code docs/coupon/coupon-v4.md} 3장이 "재서 정할 값" 으로
 * 잡은 것들이라, 여기 기본값은 시작점일 뿐이고 부하 시험에서 옮겨 가며 정한다.
 *
 * <p>이 값들을 흩어 두지 않고 한곳에 모은 이유가 둘이다. 하나는 값들이 서로의 상하한이라 따로
 * 고치면 계층이 역전된다는 것이고, 다른 하나는 시험에서 함께 움직이는 값이라 한 파일에 있어야
 * 무엇을 바꿔 무엇이 달라졌는지 사람이 읽을 수 있다는 것이다.
 *
 * @param reclaimAfter  이만큼 커밋되지 않은 순번은 소진 시점에 회수해 다른 회원에게 넘긴다
 * @param batchWindow   첫 항목을 꺼낸 뒤 이만큼 더 모으고 플러시한다
 * @param batchSize     윈도우를 다 기다리지 않고 끊는 상한이다
 * @param flushThreads  플러시 스레드 수. 1 부터 늘려가며 잰다
 * @param queueCapacity 큐 상한. 기본은 사실상 무한이고 시험에서 줄여간다
 * @param commitWait 요청 스레드가 자기 발급의 확정을 기다리는 시간. 넘으면 혼잡으로 끊는다.
 *                   재는 구간은 큐에 넣은 뒤부터 DB 커밋과 Redis 확정 표시가 끝날 때까지다.
 *                   순번 확보와 응답 직렬화는 이 안에 없다
 * @param rebuildContributeWait Redis 재건을 주도하는 인스턴스가 남들이 자기 큐를 올리기를
 *                              기다리는 시간. 살아 있으면 밀리초에 끝나고, 죽었으면 이만큼 기다린 뒤
 *                              그 큐를 없는 것으로 친다
 * @param couponCacheTtl 마감이 없는 쿠폰의 스냅샷을 이 JVM 이 들고 있는 시간.
 *                       마감이 있으면 이 값을 안 쓰고 Redis 키와 같은 시각에 버린다
 */
@ConfigurationProperties("coupon.issue")
public record CouponIssueProperties(
        @DefaultValue("60s") Duration reclaimAfter,
        @DefaultValue("20ms") Duration batchWindow,
        @DefaultValue("500") int batchSize,
        @DefaultValue("1") int flushThreads,
        @DefaultValue("2147483647") int queueCapacity,
        @DefaultValue("2s") Duration commitWait,
        @DefaultValue("3s") Duration rebuildContributeWait,
        @DefaultValue("5s") Duration couponCacheTtl) {

    public CouponIssueProperties {
        require(batchSize >= 1, "batchSize 는 1 이상이어야 한다");
        require(flushThreads >= 1, "flushThreads 는 1 이상이어야 한다");
        require(queueCapacity >= 1, "queueCapacity 는 1 이상이어야 한다");
        require(!batchWindow.isNegative(), "batchWindow 는 음수일 수 없다");
        require(!couponCacheTtl.isNegative(), "couponCacheTtl 은 음수일 수 없다");
        require(rebuildContributeWait.isPositive(), "rebuildContributeWait 는 양수여야 한다");

        /*
         * 회수 기준이 확정 대기보다 짧으면 회수가 아직 살아 있는 요청의 번호를 뺏는다.
         * 그러면 그 둘이 같은 번호로 INSERT 해 uk_mc_coupon_seq 에 걸리므로, 이 검사가 기동에서 막는다.
         */
        require(reclaimAfter.compareTo(commitWait) > 0,
                "reclaimAfter(" + reclaimAfter + ") 는 commitWait(" + commitWait + ") 보다 길어야 한다");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
