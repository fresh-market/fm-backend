package com.freshmarket.coupon.domain.issue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.freshmarket.coupon.domain.redis.CouponSeqCommitter;
import com.freshmarket.coupon.domain.repository.MemberCouponBulkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 큐를 모아 벌크로 쓰고 결과를 요청 스레드에게 돌려준다.
 *
 * <p>실패를 보는 유일한 자리라 반납도 여기서 한다. 요청 스레드는 future 만 기다리고 무엇이
 * 잘못됐는지 모른다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>스레드는 플랫폼 스레드다. 하는 일이 JDBC 블로킹이고 개수를 정확히 N 으로 잡아 재는 것이
 * 목적이라, 가상 스레드로 두면 그 N 이 의미를 잃는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueFlusher implements SmartLifecycle {

    private static final long SHUTDOWN_WAIT_SECONDS = 10;

    private final CouponIssueQueue queue;
    private final MemberCouponBulkRepository bulkRepository;
    private final CouponSeqCommitter committer;
    private final CouponIssueProperties properties;

    private volatile boolean running;
    private ExecutorService executor;

    @Override
    public void start() {
        int threads = properties.flushThreads();
        running = true;
        executor = Executors.newFixedThreadPool(threads, namedThreadFactory());
        for (int i = 0; i < threads; i++) {
            executor.submit(this::loop);
        }
        log.info("event=COUPON_FLUSHER_STARTED threads={} batchSize={} batchWindow={}",
                threads, properties.batchSize(), properties.batchWindow());
    }

    /*
     * 이 메서드는 돌던 배치를 끝까지 마치게 둔다.
     * shutdownNow 로 끊으면 이미 커밋된 행의 확정 표시를 못 남기고, 그만큼 다음 요청이 DB 로 간다.
     */
    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        drainLeftovers();
        log.info("event=COUPON_FLUSHER_STOPPED");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /*
     * 이 메서드가 종료 시점에 큐에 남은 것을 한 번 더 쓴다.
     * 그때도 못 쓴 건은 순번이 pending 에 남아 나중에 회수되므로 재고로 돌아온다.
     */
    private void drainLeftovers() {
        List<IssueTicket> leftovers = new ArrayList<>();
        queue.drainAll(leftovers);
        if (leftovers.isEmpty()) {
            return;
        }
        log.warn("event=COUPON_FLUSHER_LEFTOVERS size={}", leftovers.size());
        try {
            flush(leftovers);
        } catch (RuntimeException e) {
            log.error("event=COUPON_FLUSHER_LEFTOVER_FAILED size={}", leftovers.size(), e);
            failAll(leftovers);
        }
    }

    private void loop() {
        long windowNanos = properties.batchWindow().toNanos();
        int batchSize = properties.batchSize();
        List<IssueTicket> batch = new ArrayList<>(batchSize);

        while (running) {
            try {
                IssueTicket first = queue.poll(windowNanos);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                fillWithinWindow(batch, batchSize, windowNanos);

                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failAll(batch);
                return;
            } catch (RuntimeException e) {
                // 한 배치가 터져도 플러시 스레드는 살아 있어야 한다. 남은 요청까지 같이 굶길 수 없다
                log.error("event=COUPON_FLUSH_BATCH_FAILED size={}", batch.size(), e);
                failAll(batch);
            } finally {
                batch.clear();
            }
        }
    }

    /*
     * 플러시 스레드가 첫 항목을 잡은 뒤 윈도우가 닫힐 때까지 더 모은다.
     * 배치가 다 차면 그 스레드는 윈도우를 안 기다리고 바로 나간다. 한산할 때만 윈도우만큼 기다리는 셈이다.
     */
    private void fillWithinWindow(List<IssueTicket> batch, int batchSize, long windowNanos)
            throws InterruptedException {
        long deadline = System.nanoTime() + windowNanos;
        while (batch.size() < batchSize) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            IssueTicket next = queue.poll(remaining);
            if (next == null) {
                return;
            }
            batch.add(next);
            queue.drainTo(batch, batchSize - batch.size());
        }
    }

    private void flush(List<IssueTicket> batch) {
        try {
            bulkRepository.insertAll(batch);
        } catch (DataAccessException e) {
            /*
             * DB 는 어느 행 때문에 걸렸는지 알려주지 않으므로, 이 메서드가 한 건씩 다시 넣어 가른다.
             * 앞선 배치가 일부는 넣었을 수 있다. 그래서 다시 넣다 걸린 것이 남의 행인지 이 요청
             * 자신의 행인지를 resolveDuplicate 가 순번으로 갈라야 한다.
             */
            flushOneByOne(batch);
            return;
        }
        completeIssued(batch);
    }

    private void flushOneByOne(List<IssueTicket> batch) {
        List<IssueTicket> issued = new ArrayList<>(batch.size());
        for (IssueTicket ticket : batch) {
            try {
                bulkRepository.insertOne(ticket);
                issued.add(ticket);
            } catch (DuplicateKeyException e) {
                resolveDuplicate(ticket);
            } catch (DataAccessException e) {
                /*
                 * 이 스레드는 커밋이 됐는지 아닌지 모른다.
                 * 여기서 반납하면 남이 쓰는 번호를 내줄 수 있으므로 매핑을 그대로 두어, 재시도가 같은
                 * 번호로 오게 한다. 그 회원이 안 돌아오면 pending 이 시간으로 회수한다.
                 */
                log.warn("event=COUPON_ISSUE_WRITE_FAILED couponId={} memberId={} seq={}",
                        ticket.couponId(), ticket.memberId(), ticket.issueSeq(), e);
                ticket.complete(new IssueOutcome.Congested());
            }
        }
        completeIssued(issued);
    }

    /*
     * 이 메서드는 어느 UNIQUE 에 걸렸는지를 예외 메시지가 아니라 실제 행을 읽어 가른다.
     * 메시지 형식은 드라이버와 서버 판에 따라 달라지지만 행의 유무는 달라지지 않는다.
     */
    private void resolveDuplicate(IssueTicket ticket) {
        Optional<Integer> actualSeq;
        try {
            actualSeq = bulkRepository.findIssuedSeq(ticket.couponId(), ticket.memberId());
        } catch (DataAccessException e) {
            // 가릴 수 없으면 이 메서드는 아무것도 건드리지 않는다. 매핑을 유지하는 쪽이 언제나 안전하다
            log.warn("event=COUPON_ISSUE_CLASSIFY_FAILED couponId={} memberId={}",
                    ticket.couponId(), ticket.memberId(), e);
            ticket.complete(new IssueOutcome.Congested());
            return;
        }

        if (actualSeq.isPresent()) {
            int actual = actualSeq.get();

            /*
             * 이번에 받은 번호로 이미 행이 있다. 앞선 시도가 썼고 확정 표시만 못 남긴 것이다.
             * 그 번호는 살아 있으므로 반납하면 안 된다. 표시만 마저 남긴다.
             */
            if (actual == ticket.issueSeq()) {
                committer.markCommitted(ticket.couponId(), Map.of(ticket.memberId(), actual));
                ticket.complete(new IssueOutcome.AlreadyIssued(actual));
                return;
            }

            /*
             * 원래 갖고 있던 번호가 따로 있다. 이번 번호는 아무도 안 썼으므로 반납한다.
             * 이 경로는 Redis 가 매핑을 잃은 뒤에 다시 온 회원에게 생긴다.
             */
            committer.returnAndRepair(ticket.couponId(), ticket.memberId(), ticket.issueSeq(), actual);
            ticket.complete(new IssueOutcome.AlreadyIssued(actual));
            return;
        }

        // uk_mc_coupon_seq 다. 그 번호는 남이 쓰고 있으므로 이 메서드가 반납하지 않는다
        log.warn("event=COUPON_ISSUE_SEQ_TAKEN couponId={} memberId={} seq={}",
                ticket.couponId(), ticket.memberId(), ticket.issueSeq());
        committer.dropMapping(ticket.couponId(), ticket.memberId());
        ticket.complete(new IssueOutcome.Congested());
    }

    /*
     * 플러시 스레드가 확정 표시를 붙이고 나서 응답한다.
     * 순서를 뒤집으면 사용자의 재시도가 표시보다 먼저 도착해 DB 까지 간다. 그 왕복을 아끼려고
     * 두는 표시라 응답 앞에 있어야 뜻이 있다.
     */
    private void completeIssued(List<IssueTicket> issued) {
        if (issued.isEmpty()) {
            return;
        }
        markCommitted(issued);
        for (IssueTicket ticket : issued) {
            ticket.complete(new IssueOutcome.Issued(ticket.issueSeq()));
        }
    }

    // 한 배치에 여러 쿠폰이 섞일 수 있으므로 이 메서드가 쿠폰별로 묶어 각각 한 번씩 갱신한다
    private void markCommitted(List<IssueTicket> issued) {
        Map<Long, Map<Long, Integer>> byCoupon = new HashMap<>();
        for (IssueTicket ticket : issued) {
            byCoupon.computeIfAbsent(ticket.couponId(), key -> new HashMap<>())
                    .put(ticket.memberId(), ticket.issueSeq());
        }
        byCoupon.forEach(committer::markCommitted);
    }

    private void failAll(List<IssueTicket> batch) {
        for (IssueTicket ticket : batch) {
            ticket.complete(new IssueOutcome.Congested());
        }
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "coupon-flush-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
