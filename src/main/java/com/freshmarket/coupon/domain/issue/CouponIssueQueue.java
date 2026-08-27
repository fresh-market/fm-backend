package com.freshmarket.coupon.domain.issue;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

/**
 * 인스턴스별 인메모리 큐다. 같은 프로세스가 넣고 빼므로 이것으로 족하다
 * ({@code docs/coupon/coupon.md} 7장).
 *
 * <p>자리를 순번 확보보다 먼저 판정하는 것이 이 클래스의 요점이다. 순번을 받고 나서 큐에 못 넣으면
 * 그 번호를 반납해야 하는데, 순서를 뒤집으면 그 경로가 아예 안 생긴다
 * ({@code docs/coupon/coupon-v4.md} 3장).
 *
 * <p>큐 자체는 무한으로 두고 상한은 {@link #hasRoom()} 이 본다. 세마포어로 정확히 세지 않는 이유가
 * 둘이다. 하나는 요청마다 경합하는 CAS 가 둘 늘어난다는 것이고, 다른 하나는 꺼낸 만큼 자리를
 * 돌려주는 코드가 필요해져 그 사이에서 죽으면 자리가 새기 때문이다. 큐는 스스로 세므로 안 샌다.
 *
 * <p>대신 상한이 대략이 된다. 확인을 동시에 통과한 만큼 넘칠 수 있다. 이 상한의 쓰임이 꼬리 지연과
 * 급사 시 손실 건수의 크기를 잡는 것이라 정확한 수를 요구하지 않는다.
 */
@Component
public class CouponIssueQueue {

    private final LinkedBlockingQueue<IssueTicket> queue = new LinkedBlockingQueue<>();
    private final int capacity;

    public CouponIssueQueue(CouponIssueProperties properties) {
        this.capacity = properties.queueCapacity();
    }

    /** 순번을 받기 전에 부른다. 자리가 없으면 Redis 를 부르지 않고 그대로 혼잡으로 답한다. */
    public boolean hasRoom() {
        return queue.size() < capacity;
    }

    // 자리를 확인한 뒤에만 부른다. 큐가 무한이라 여기서 막히지 않는다
    public void submit(IssueTicket ticket) {
        queue.add(ticket);
    }

    /** 플러시 스레드가 첫 항목을 기다린다. 비어 있으면 그만큼 기다렸다 {@code null} 이다. */
    public IssueTicket poll(long timeoutNanos) throws InterruptedException {
        return queue.poll(timeoutNanos, TimeUnit.NANOSECONDS);
    }

    public int drainTo(Collection<IssueTicket> target, int max) {
        return queue.drainTo(target, max);
    }

    /** 종료할 때 남은 것을 꺼내 정리하려고 쓴다. */
    public int drainAll(Collection<IssueTicket> target) {
        return queue.drainTo(target);
    }

    /** 8장의 큐 최대 길이 지표가 보는 값이다. */
    public int size() {
        return queue.size();
    }
}
