package com.freshmarket.coupon.internal.batch;

import com.freshmarket.coupon.internal.redis.CouponSeqFreeSweeper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * 주인을 잃은 번호를 되살리는 쓸기를 짧은 주기로 돌린다.
 * 스케줄러 어댑터라 서비스가 아니고, internal.service 패키지(커버리지 100% 대상)에 있으면 안 된다.
 *
 * 하루 한 번 도는 정합성 검증과 한 클래스에 두지 않는다. 그쪽은 읽기 전용 검사형이고 이쪽은
 * Redis 를 고치는 배치라, 한 스케줄에 묶으면 "아무것도 안 고친다" 는 그쪽 성질이 깨진다.
 */
@Component
// 빈 자체를 batch 프로필로 묶는다. @EnableScheduling 만 끄면 빈은 남아 실수로 호출될 수 있다
@Profile("batch")
@RequiredArgsConstructor
public class CouponSeqFreeSweepScheduler {

    private final CouponSeqFreeSweeper sweeper;

    /*
     * 주기가 10초인 근거는 이벤트 길이다.
     *
     * 선착순 이벤트가 90초라 하루 한 번으로는 되살린 번호를 받을 사람이 없다. 10초면 한 이벤트에
     * 아홉 번 돌아, 승격이 창을 만든 뒤 늦어도 10초 안에 그 재고가 다시 나간다.
     *
     * 이 주기가 비싸지 않은 이유는 쓸기가 무거운 조회를 조건부로만 하기 때문이다. 발급 행 수와
     * MAX(issue_seq) 를 먼저 견주고 둘이 같으면(결번이 없다는 뜻이다) 거기서 끝낸다.
     * 정상 운영의 대부분이 그 경로다 (docs/coupon/free-sweep-2026-10-12.md 5.1).
     *
     * fixedDelay 다. fixedRate 로 두면 한 번이 주기보다 길어질 때 다음 실행이 겹쳐 쌓인다.
     * 되살리기는 늦어도 되는 일이라 겹치게 둘 이유가 없다.
     */
    @Scheduled(fixedDelayString = "${coupon.issue.free-sweep-interval:10000}")
    public void sweepFreeSeqs() {
        sweeper.sweep();
    }
}
