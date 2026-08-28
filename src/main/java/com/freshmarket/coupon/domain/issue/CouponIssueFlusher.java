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

import com.freshmarket.coupon.domain.exception.DataAccessFailures;
import com.freshmarket.coupon.domain.redis.CouponSeqCommitter;
import com.freshmarket.coupon.domain.repository.MemberCouponBulkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * 플러시 스레드가 큐에 쌓인 티켓을 모아 한 번에 DB 에 쓰고, 그 결과를 기다리던 요청 스레드에게
 * 돌려준다.
 *
 * <p><b>DB 쓰기가 어떻게 실패했는지를 아는 것은 이 클래스뿐이다.</b> 요청 스레드는 future 만
 * 기다리고 무엇이 잘못됐는지 모른다. 그래서 Redis 순번을 되돌리는 일도 이 클래스가 한다
 * ({@code docs/coupon/coupon.md} 3장).
 *
 * <p>이 클래스는 가상 스레드가 아니라 플랫폼 스레드를 쓴다. 하는 일이 JDBC 블로킹이고 그 개수를
 * 정확히 N 으로 잡아 두고 재는 것이 목적인데, 가상 스레드로 두면 실제로 몇 개가 DB 를 두드리는지
 * 알 수 없어 그 N 이 의미를 잃는다.
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
    private final CouponWriteCircuit writeCircuit;

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
     * 이 메서드는 돌고 있던 배치를 플러시 스레드가 끝까지 마치도록 기다린다.
     * shutdownNow 로 끊으면 이미 커밋된 행에 Redis 확정 표시를 못 남기고, 그만큼 그 회원들의
     * 다음 요청이 Redis 에서 안 걸러져 DB 까지 간다.
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
     * 앱이 내려갈 때 이 메서드가 큐에 남은 티켓을 한 번 더 쓴다.
     * 그때도 못 쓴 것은 그 순번이 Redis 의 pending 에 남아 있어, 나중에 회수 로직이 시간을 보고
     * 되살려 재고로 돌려놓는다.
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
            failAll(leftovers, IssueResult.ABORTED);
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
                failAll(batch, IssueResult.ABORTED);
                return;
            } catch (RuntimeException e) {
                // 한 배치가 터져도 이 플러시 스레드는 계속 돌아야 한다. 큐에 남은 요청까지 같이 굶길 수 없다
                log.error("event=COUPON_FLUSH_BATCH_FAILED size={}", batch.size(), e);
                failAll(batch, IssueResult.ABORTED);
            } finally {
                batch.clear();
            }
        }
    }

    /*
     * 플러시 스레드가 첫 항목을 잡은 뒤 배치 윈도우가 닫힐 때까지 뒤따라오는 것을 더 모은다.
     * 배치가 먼저 차면 그 스레드는 윈도우를 안 기다리고 바로 쓴다. 즉 요청이 몰릴 때는 크기가,
     * 한산할 때는 윈도우가 배치를 끊는다.
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
            writeCircuit.write(() -> {
                bulkRepository.insertAll(batch);
                return null;
            });
        } catch (DataAccessException e) {
            /*
             * 벌크 쓰기가 통째로 실패했다. DB 는 어느 행 때문에 걸렸는지 알려주지 않는다.
             * 그래서 이 플러시 스레드가 한 건씩 다시 넣어 범인을 가려낸다.
             *
             * 앞선 시도가 일부는 이미 넣었을 수 있다. 그래서 다시 넣다 중복에 걸렸을 때 그 행이
             * 남의 것인지 이 요청이 앞서 넣은 자기 것인지를 resolveDuplicate 가 순번으로 가른다.
             */
            flushOneByOne(batch);
            return;
        } catch (Exception e) {
            /*
             * DB 회로가 열려 있어 이 쓰기는 DB 까지 가지도 못했다.
             * 이 배치 전부에 혼잡으로 답한다. 한 건씩 다시 넣어 봐야 같은 회로에 그대로 막힌다.
             */
            log.warn("event=COUPON_FLUSH_CIRCUIT_OPEN size={}", batch.size());
            failAll(batch, IssueResult.WRITE_CIRCUIT);
            return;
        }
        completeIssued(batch);
    }

    private void flushOneByOne(List<IssueTicket> batch) {
        List<IssueTicket> issued = new ArrayList<>(batch.size());
        for (IssueTicket ticket : batch) {
            try {
                writeCircuit.write(() -> {
                    bulkRepository.insertOne(ticket);
                    return null;
                });
                issued.add(ticket);
            } catch (DuplicateKeyException e) {
                resolveDuplicate(ticket);
            } catch (DataAccessException e) {
                /*
                 * 이 플러시 스레드는 방금 쓰기가 커밋됐는지 아닌지 모른다.
                 *
                 * 여기서 순번을 반납하면 이미 커밋됐을 때 남이 쓰는 번호를 다른 회원에게 내주게
                 * 된다. 그래서 매핑을 그대로 두어 그 회원의 재시도가 같은 번호로 오게 한다.
                 * 그 회원이 안 돌아오면 pending 이 시간을 보고 회수한다.
                 */
                log.warn("event=COUPON_ISSUE_WRITE_FAILED couponId={} memberId={} seq={} transient={}",
                        ticket.couponId(), ticket.memberId(), ticket.issueSeq(),
                        DataAccessFailures.isTransient(e), e);
                ticket.complete(outcomeFor(e));
            } catch (Exception e) {
                // DB 회로가 열려 이 쓰기는 DB 까지 안 갔다. 그러니 그 번호는 이 회원 것으로 그대로 둔다
                ticket.complete(new IssueOutcome.Congested(IssueResult.WRITE_CIRCUIT));
            }
        }
        completeIssued(issued);
    }

    /*
     * 이 메서드는 어느 UNIQUE 제약에 걸린 것인지를 예외 메시지가 아니라 실제 행을 읽어서 가른다.
     * 메시지 형식은 드라이버와 서버 판에 따라 달라지지만, 그 회원의 행이 있느냐 없느냐는 안 달라진다.
     */
    private void resolveDuplicate(IssueTicket ticket) {
        Optional<Integer> actualSeq;
        try {
            actualSeq = bulkRepository.findIssuedSeq(ticket.couponId(), ticket.memberId());
        } catch (DataAccessException e) {
            // 읽지도 못했으면 이 메서드는 Redis 를 건드리지 않는다. 매핑을 그대로 두는 쪽이 언제나 안전하다
            log.warn("event=COUPON_ISSUE_CLASSIFY_FAILED couponId={} memberId={}",
                    ticket.couponId(), ticket.memberId(), e);
            ticket.complete(outcomeFor(e));
            return;
        }

        if (actualSeq.isPresent()) {
            int actual = actualSeq.get();

            /*
             * 이 회원이 가진 행의 순번이 이번에 받은 번호와 같다.
             * 앞선 시도가 이미 썼고 Redis 확정 표시만 못 남긴 것이라, 그 번호는 살아 있다.
             * 반납하면 남이 쓰는 번호를 내주게 되므로 표시만 마저 남긴다.
             */
            if (actual == ticket.issueSeq()) {
                committer.markCommitted(ticket.couponId(), Map.of(ticket.memberId(), actual));
                ticket.complete(new IssueOutcome.AlreadyIssued(actual));
                return;
            }

            /*
             * 이 회원이 원래 갖고 있던 번호가 따로 있다. 이번에 받은 번호는 아무도 안 썼으므로 반납한다.
             * Redis 가 매핑을 잃은 뒤에 그 회원이 다시 왔을 때 이 경로로 온다.
             */
            committer.returnAndRepair(ticket.couponId(), ticket.memberId(), ticket.issueSeq(), actual);
            ticket.complete(new IssueOutcome.AlreadyIssued(actual));
            return;
        }

        // 그 회원의 행이 없으니 uk_mc_coupon_seq 에 걸린 것이다. 그 번호는 남이 쓰고 있어 반납하면 안 된다
        log.warn("event=COUPON_ISSUE_SEQ_TAKEN couponId={} memberId={} seq={}",
                ticket.couponId(), ticket.memberId(), ticket.issueSeq());
        committer.dropMapping(ticket.couponId(), ticket.memberId());
        ticket.complete(new IssueOutcome.Congested(IssueResult.SEQ_TAKEN));
    }

    /*
     * 플러시 스레드가 Redis 확정 표시를 먼저 붙이고 그 다음에 요청 스레드를 깨운다.
     * 순서를 뒤집으면 응답을 받은 사용자의 재시도가 표시보다 먼저 도착해 DB 까지 간다.
     * 그 왕복을 아끼려고 두는 표시라 응답보다 앞에 있어야 뜻이 있다.
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

    // 한 배치에 여러 쿠폰의 티켓이 섞일 수 있어, 이 메서드가 쿠폰별로 묶어 쿠폰마다 한 번씩 Redis 를 친다
    private void markCommitted(List<IssueTicket> issued) {
        Map<Long, Map<Long, Integer>> byCoupon = new HashMap<>();
        for (IssueTicket ticket : issued) {
            byCoupon.computeIfAbsent(ticket.couponId(), key -> new HashMap<>())
                    .put(ticket.memberId(), ticket.issueSeq());
        }
        byCoupon.forEach(committer::markCommitted);
    }

    /*
     * 이 메서드는 잠시 뒤에 다시 하면 될 실패만 혼잡으로 바꾼다.
     * SQL 문법 오류처럼 사람이 고쳐야 하는 것까지 "잠시 후 다시" 로 덮으면 사용자의 재시도에
     * 그 버그가 묻힌다. 그런 것은 실패로 두어 서버 오류로 드러나게 한다.
     */
    private static IssueOutcome outcomeFor(DataAccessException e) {
        if (DataAccessFailures.isTransient(e)) {
            return new IssueOutcome.Congested(IssueResult.DB_FAILED);
        }
        return new IssueOutcome.Failed();
    }

    private void failAll(List<IssueTicket> batch, IssueResult reason) {
        for (IssueTicket ticket : batch) {
            ticket.complete(new IssueOutcome.Congested(reason));
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
