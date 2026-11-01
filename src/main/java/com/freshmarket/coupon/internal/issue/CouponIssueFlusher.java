package com.freshmarket.coupon.internal.issue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicInteger;

import com.freshmarket.coupon.internal.exception.DataAccessFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.freshmarket.coupon.internal.redis.CouponSeqCommitter;
import com.freshmarket.coupon.internal.repository.MemberCouponBulkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    /*
     * 늦게 푸는 참조다. 이 클래스를 쓰는 쪽(재건)이 이 클래스를 다시 쓰기 때문에 빈 사이에 고리가 생긴다.
     *
     *   Flusher -> 재건 신호 -> 재건기 -> 큐를 올리는 쪽 -> Flusher
     *
     * 고리를 끊을 자리는 여기다. 나머지 셋은 서로를 진짜로 필요로 하는데, 이 참조만 "일이 생기면
     * 알려 준다" 는 바깥 방향이라 기동 시점에 있을 이유가 없다.
     */
    private final ObjectProvider<CouponSeqRebuildSignal> rebuildSignal;
    private final MeterRegistry registry;

    /*
     * commit-wait 예산 720ms 가 어디로 가는지 보려고 붙였다 (2026-10-10).
     *
     * 그전에는 커넥션 획득만 HikariCP 가 재고 나머지는 아무도 안 쟀다. 무장애 회차가 p99
     * 541밀리초인데 그 시간이 어디 있는지 못 짚었다. 셋을 가르면 예산 항목과 맞출 수 있다.
     *
     *   큐 대기    티켓을 넣은 뒤 플러시가 집을 때까지.  예산에서 유일하게 상한이 없다
     *   배치 쓰기  INSERT 실행과 커밋.  socketTimeout 300ms 가 맞는지 본다
     *   확정 표시  markCommitted 한 번.  redis timeout 100ms 와 견준다
     */
    private Timer queueWait;
    private Timer batchWrite;
    private Timer markCommit;

    private volatile boolean running;

    private ExecutorService executor;

    @Override
    public void start() {
        int threads = properties.flushThreads();
        /*
         * 기동 때 만들어 둔다. 첫 표본에서 만들면 "표본이 0 건" 과 "계량기가 없다" 가 안 갈린다.
         * 이 저장소가 계량기를 기동 때 다 만들어 두는 규칙을 따른다.
         */
        queueWait = Timer.builder("coupon.issue.queue.wait")
                .description("티켓이 큐에서 기다린 시간. commit-wait 예산에서 상한이 없는 항목이다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        batchWrite = Timer.builder("coupon.issue.batch.write")
                .description("벌크 INSERT 의 실행과 커밋. socketTimeout 300ms 와 견준다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        markCommit = Timer.builder("coupon.issue.mark.commit")
                .description("확정 표시 한 번. redis timeout 100ms 와 견준다")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
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

    /**
     * 톰캣보다 <b>뒤에</b> 멈춘다. 기본값을 쓰면 반대가 된다.
     *
     * <p>스프링은 phase 가 높은 것부터 멈춘다({@code DefaultLifecycleProcessor.stopBeans} 가
     * {@code Comparator.reverseOrder()} 로 정렬한다). {@link SmartLifecycle} 의 기본값은
     * {@code Integer.MAX_VALUE} 라 아무것도 재정의하지 않으면 이 빈이 제일 먼저 죽는다.
     * 웹 계층은 두 단계로 나뉜다.
     *
     * <pre>
     * MAX - 1024   WebServerGracefulShutdownLifecycle   진행 중 요청을 기다린다
     * MAX - 2048   WebServerStartStopLifecycle          웹 서버를 실제로 닫는다
     * </pre>
     *
     * <p>그래서 -2048 로는 모자란다. 웹 서버를 닫는 빈과 <b>같은 phase 라 함께 멈추고</b>,
     * 같은 단계 안에서는 순서가 보장되지 않는다. 그 아래로 내려야 확실히 뒤에 온다.
     *
     * <p>먼저 죽으면 그 뒤에 처리되는 요청이 Redis 순번을 받아 <b>아무도 안 읽는 큐에 넣는다.</b>
     * {@code CouponIssueQueue.submit} 에는 종료 가드가 없고 {@code drainLeftovers} 는 이미
     * 지나간 뒤라, 그 순번은 발급되지 않은 채 재고에서 빠진다.
     *
     * <p>생산자와 소비자의 관계로 보면 방향이 분명하다. 톰캣이 큐에 넣고 이 빈이 꺼내 쓴다.
     * <b>끌 때는 생산자를 먼저 멈춰야</b> 하고, 정지 순서가 내림차순이므로 소비자인 이쪽이
     * 더 낮아야 한다. 켤 때는 오름차순이라 이 값이 낮으면 소비자가 먼저 뜨는데, 그것도 맞다.
     * 첫 요청이 오기 전에 꺼내 갈 쪽이 이미 돌고 있어야 한다.
     */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 4096;
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

                long couponId = batch.get(0).couponId();
                long pickedAt = System.nanoTime();
                for (IssueTicket ticket : batch) {
                    queueWait.record(pickedAt - ticket.queuedAtNanos(), TimeUnit.NANOSECONDS);
                }

                flush(batch);
                /*
                 * 재건이 도는지 배치마다 한 번 본다. 요청당이 아니라 배치당이라 값이 싸다.
                 * 이 확인이 없으면 요청을 못 받는 인스턴스가 자기 큐를 영영 안 올린다 (coupon.md 9장).
                 *
                 * 방금 쓴 최대 순번을 함께 넘긴다. 카운터가 그보다 작으면 깨질 수 없는 식이 깨진
                 * 것이고, 그 비교를 여기서만 공짜로 할 수 있다. 번호를 쥔 쪽이 이 스레드다.
                 */
                rebuildSignal.getObject().checkAfterFlush(couponId, maxIssuedSeq(batch));
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
            long writeStartedAt = System.nanoTime();
            writeCircuit.write(() -> {
                bulkRepository.insertAll(batch);
                return null;
            });
            batchWrite.record(System.nanoTime() - writeStartedAt, TimeUnit.NANOSECONDS);
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
        /*
         * 중복 해소의 세 경로가 여기에 쌓인다. 셋 다 Redis 뒷정리일 뿐이고 그 티켓의 응답은
         * DB 에서 읽은 순번으로 이미 정해져 있어서, 이 회분을 다 처리할 때까지 미뤄도 답이 안 바뀐다.
         * 미루면 티켓마다 치던 것을 쿠폰마다 한 번으로 묶을 수 있다.
         */
        Map<Long, Map<Long, Integer>> marks = new HashMap<>();
        Map<Long, List<CouponSeqCommitter.Repair>> repairs = new HashMap<>();
        List<IssueTicket> seqTaken = new ArrayList<>();
        for (IssueTicket ticket : batch) {
            try {
                writeCircuit.write(() -> {
                    bulkRepository.insertOne(ticket);
                    return null;
                });
                issued.add(ticket);
            } catch (DuplicateKeyException e) {
                resolveDuplicate(ticket, marks, repairs, seqTaken);
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
        completeIssued(issued, marks);
        repairs.forEach(committer::returnAndRepairs);
        repairSeqTaken(seqTaken);
    }

    /*
     * 이 메서드는 어느 UNIQUE 제약에 걸린 것인지를 예외 메시지가 아니라 실제 행을 읽어서 가른다.
     * 메시지 형식은 드라이버와 서버 판에 따라 달라지지만, 그 회원의 행이 있느냐 없느냐는 안 달라진다.
     */
    private void resolveDuplicate(IssueTicket ticket,
                                  Map<Long, Map<Long, Integer>> marks,
                                  Map<Long, List<CouponSeqCommitter.Repair>> repairs,
                                  List<IssueTicket> seqTaken) {
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
                mark(marks, ticket.couponId(), ticket.memberId(), actual);
                ticket.complete(new IssueOutcome.AlreadyIssued(actual));
                return;
            }

            /*
             * 이 회원이 원래 갖고 있던 번호가 따로 있다. 이번에 받은 번호는 아무도 안 썼으므로 반납한다.
             * Redis 가 매핑을 잃은 뒤에 그 회원이 다시 왔을 때 이 경로로 온다.
             */
            repairs.computeIfAbsent(ticket.couponId(), key -> new ArrayList<>())
                    .add(new CouponSeqCommitter.Repair(ticket.memberId(), ticket.issueSeq(), actual));
            recordRepair(ticket, actual);
            ticket.complete(new IssueOutcome.AlreadyIssued(actual));
            return;
        }

        /*
         * 그 회원의 행이 없으니 uk_mc_coupon_seq 에 걸린 것이다. 그 번호는 남이 쓰고 있어 반납하면 안 된다.
         * 뒷정리는 이 회분을 다 처리한 뒤에 한다. 이 티켓의 응답은 이미 정해졌고 남은 일이 제3자를 고치는 것이라
         * 응답 경로 위에 없다 (docs/coupon/coupon.md 3장).
         */
        log.warn("event=COUPON_ISSUE_SEQ_TAKEN couponId={} memberId={} seq={}",
                ticket.couponId(), ticket.memberId(), ticket.issueSeq());
        seqTaken.add(ticket);
        ticket.complete(new IssueOutcome.Congested(IssueResult.SEQ_TAKEN));
    }

    /**
     * 회수가 잘못 짚어 남의 번호를 내준 것을 되돌린다.
     *
     * <p>{@code uk_mc_coupon_seq} 위반은 <b>그 번호를 쓰는 행이 DB 에 있다</b>는 증거다. 그런
     * 상태가 되는 길은 하나뿐이다. 그 행의 주인이 커밋까지 마쳤는데 확정 표시를 못 남겨,
     * 회수가 그 매핑을 버려진 것으로 보고 지운 뒤 번호를 남에게 넘긴 것이다.
     *
     * <p>그래서 이 메서드가 주인을 찾아 확정 표시를 되살린다. 그러면 주인이 다시 왔을 때 소진이
     * 아니라 이미 발급으로 답하고, 주인이 {@code pending} 에서 빠져 그 번호가 다시 훔쳐지지 않는다.
     * <b>이것이 없으면 같은 번호가 사람을 계속 태운다.</b>
     */
    private void repairSeqTaken(List<IssueTicket> seqTaken) {
        if (seqTaken.isEmpty()) {
            return;
        }
        Map<Long, List<IssueTicket>> byCoupon = new HashMap<>();
        for (IssueTicket ticket : seqTaken) {
            byCoupon.computeIfAbsent(ticket.couponId(), key -> new ArrayList<>()).add(ticket);
        }
        byCoupon.forEach(this::repairSeqTaken);
    }

    private void repairSeqTaken(long couponId, List<IssueTicket> seqTaken) {
        committer.dropMappings(couponId, seqTaken.stream().map(IssueTicket::memberId).toList());

        List<Integer> seqs = seqTaken.stream().map(IssueTicket::issueSeq).distinct().toList();
        Map<Integer, Long> owners;
        try {
            owners = bulkRepository.findOwners(couponId, seqs);
        } catch (DataAccessException e) {
            /*
             * 주인을 못 찾았으면 아무것도 안 고친다. 이 티켓들의 응답은 이미 나갔고,
             * 못 고친 주인은 재시도할 때 uk_mc_coupon_member 경로가 표시를 마저 붙인다.
             */
            log.warn("event=COUPON_SEQ_OWNER_LOOKUP_FAILED couponId={} size={}", couponId, seqs.size(), e);
            return;
        }

        Map<Long, Integer> seqByOwner = new HashMap<>(owners.size());
        owners.forEach((seq, owner) -> seqByOwner.put(owner, seq));
        log.warn("event=COUPON_SEQ_TAKEN_REPAIRED couponId={} taken={} owners={}",
                couponId, seqTaken.size(), seqByOwner.size());
        committer.markCommitted(couponId, seqByOwner);
    }

    /*
     * 플러시 스레드가 Redis 확정 표시를 먼저 붙이고 그 다음에 요청 스레드를 깨운다.
     * 순서를 뒤집으면 응답을 받은 사용자의 재시도가 표시보다 먼저 도착해 DB 까지 간다.
     * 그 왕복을 아끼려고 두는 표시라 응답보다 앞에 있어야 뜻이 있다.
     */
    private void completeIssued(List<IssueTicket> issued) {
        completeIssued(issued, new HashMap<>());
    }

    /**
     * 확정 표시를 붙이고 기다리던 요청 스레드를 깨운다.
     *
     * <p>커밋에 성공한 티켓과 <b>이미 자기 번호로 써 있던 회원</b>을 한 맵에 담아 쿠폰마다 한 번씩
     * 친다. 둘이 하는 일이 같아서다. 앞엣것은 방금 행이 됐고 뒤엣것은 앞선 시도가 이미 썼는데
     * 표시만 못 남긴 것인데, 남길 표시는 똑같이 {@code 회원 -> "순번:1"} 이다.
     *
     * <p>한 회분에 여러 쿠폰이 섞일 수 있어 쿠폰별로 묶는다.
     */
    private void completeIssued(List<IssueTicket> issued, Map<Long, Map<Long, Integer>> marks) {
        for (IssueTicket ticket : issued) {
            mark(marks, ticket.couponId(), ticket.memberId(), ticket.issueSeq());
        }
        long markStartedAt = System.nanoTime();
        marks.forEach(committer::markCommitted);
        markCommit.record(System.nanoTime() - markStartedAt, TimeUnit.NANOSECONDS);
        for (IssueTicket ticket : issued) {
            ticket.complete(new IssueOutcome.Issued(ticket.issueSeq()));
        }
    }

    /*
     * 이 회원이 번호를 두 번 받았다. 그 사실과 방향을 남긴다.
     *
     * 방향이 요점이다. 두 번호 중 어느 쪽이 DB 에 남았느냐가 선착순이 지켜졌는지를 가른다.
     *
     *   kept < burned   먼저 받은 번호가 남았다.  순서가 지켜졌다
     *   kept > burned   나중에 받은 번호가 남았다.  이 회원이 맨 뒤로 갔다
     *
     * 뒤쪽이 재건 중 발급을 막는 설계가 0 으로 만들려는 수다
     * (docs/coupon/rebuild-v3/design.md 8장). 세는 자리가 없어서 지금까지 한 번도
     * 못 쟀고, 그래서 순서가 실제로 얼마나 깨지고 있었는지 아무도 모른다.
     */
    private void recordRepair(IssueTicket ticket, int actual) {
        boolean inverted = actual > ticket.issueSeq();
        Counter.builder("coupon.issue.seq.repaired")
                .tag("inverted", Boolean.toString(inverted))
                .description("한 회원이 번호를 두 번 받아 하나를 반납한 건수. inverted 가 선착순이 깨진 쪽이다")
                .register(registry)
                .increment();
        log.warn("event=COUPON_ISSUE_SEQ_REPAIRED couponId={} memberId={} burned={} kept={} inverted={}",
                ticket.couponId(), ticket.memberId(), ticket.issueSeq(), actual, inverted);
    }

    private static void mark(Map<Long, Map<Long, Integer>> marks, long couponId, long memberId, int seq) {
        marks.computeIfAbsent(couponId, key -> new HashMap<>()).put(memberId, seq);
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

    /*
     * 이 배치에 든 가장 큰 순번이다.
     * 행이 되었는지는 안 본다. 번호가 나간 순간 이미 카운터를 지나왔으므로, 실패한 티켓의
     * 번호도 카운터보다 작거나 같아야 하는 것은 같다.
     */
    private static int maxIssuedSeq(List<IssueTicket> batch) {
        int max = 0;
        for (IssueTicket ticket : batch) {
            max = Math.max(max, ticket.issueSeq());
        }
        return max;
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
