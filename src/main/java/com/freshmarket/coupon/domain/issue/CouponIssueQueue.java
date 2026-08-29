package com.freshmarket.coupon.domain.issue;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * 요청 스레드가 넣고 플러시 스레드가 빼 가는 큐다. 이 JVM 의 힙에만 있고 인스턴스끼리 나눠 갖지
 * 않는다. 넣는 쪽과 빼는 쪽이 같은 프로세스라 그것으로 족하다({@code docs/coupon/coupon.md} 7장).
 *
 * <p><b>요청 스레드는 순번을 받기 전에 {@link #hasRoom()} 으로 자리를 먼저 본다.</b> 순번을 먼저
 * 받고 나서 큐에 못 넣으면 그 번호를 Redis 에 반납해야 하는데, 순서를 이렇게 두면 반납할 일
 * 자체가 안 생긴다({@code docs/coupon/coupon-v4.md} 3장).
 *
 * <p>큐 자체는 상한 없이 두고 {@link #hasRoom()} 이 설정값과 견준다. 세마포어로 자리를 정확히
 * 세지 않은 이유가 둘이다.
 *
 * <pre>
 * 비용   요청마다 여러 스레드가 다투는 CAS 연산이 둘 는다
 * 누수   플러시 스레드가 꺼낸 만큼 자리를 돌려줘야 하고, 그 사이에 죽으면 자리가 영영 안 돌아온다
 * </pre>
 *
 * <p>큐가 자기 길이를 스스로 세므로 이 방식은 자리가 새지 않는다. 대신 상한이 정확하지 않아,
 * 여러 요청 스레드가 확인을 동시에 통과한 만큼 넘칠 수 있다. 이 상한이 정하는 것은 꼬리 지연과
 * <b>앱이 급사했을 때 잃는 건수</b>의 크기라 정확한 수를 요구하지 않는다.
 */
@Component
public class CouponIssueQueue {

    private final LinkedBlockingQueue<IssueTicket> queue = new LinkedBlockingQueue<>();
    private final int capacity;

    public CouponIssueQueue(CouponIssueProperties properties) {
        this.capacity = properties.queueCapacity();
    }

    /** 요청 스레드가 순번을 받기 전에 부른다. 자리가 없으면 Redis 를 안 부르고 그대로 혼잡으로 답한다. */
    public boolean hasRoom() {
        return queue.size() < capacity;
    }

    // 요청 스레드가 hasRoom 으로 자리를 본 뒤에만 부른다. 큐에 상한이 없어 이 호출은 안 막힌다
    public void submit(IssueTicket ticket) {
        queue.add(ticket);
    }

    /** 플러시 스레드가 배치의 첫 항목을 기다린다. 그때까지 안 들어오면 {@code null} 을 돌려준다. */
    public IssueTicket poll(long timeoutNanos) throws InterruptedException {
        return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    public int drainTo(Collection<IssueTicket> target, int max) {
        return queue.drainTo(target, max);
    }

    /** 앱이 내려갈 때 플러시 스레드가 남은 것을 전부 꺼내 정리하려고 부른다. */
    public int drainAll(Collection<IssueTicket> target) {
        return queue.drainTo(target);
    }

    /** 지표가 읽는 현재 길이다. 8장이 요구한 "큐 최대 길이" 는 대시보드가 이 값에서 뽑는다. */
    public int size() {
        return queue.size();
    }
}
