package com.freshmarket.coupon.domain.redis;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 요청이 순번을 못 받았을 때 재건을 한 번 띄운다. 감지를 요청 경로에 두고 일은 뒤 스레드가 한다.
 *
 * <p><b>요청 스레드가 재건을 직접 하면 안 된다.</b> 이벤트가 열리는 순간에는 수만 개가 동시에
 * 같은 응답을 받으므로, 그 자리에서 일을 시키면 수만 개가 같은 재건을 하려 든다. 여기서는
 * 후보를 집합에 넣기만 하고 돌아간다.
 *
 * <p><b>주기 실행이 아니다.</b> {@code add} 가 거짓을 주는 것이 곧 "남이 이미 시작했다" 라서,
 * 첫 요청 하나만 작업을 띄우고 나머지는 그대로 돌아간다. 평소에는 도는 것이 없다.
 *
 * <p>재시도가 따로 필요 없다. 재건이 실패하면 집합에서 빠지고, 이벤트가 도는 동안에는 다음
 * 요청이 또 같은 응답을 받아 다시 띄운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponSeqRebuildTrigger {

    private final Set<Long> inProgress = ConcurrentHashMap.newKeySet();

    /*
     * 스레드 하나로 족하다. 재건은 이벤트당 많아야 몇 번 도는 일이고, 둘이 붙어 봐야 같은 락을
     * 다툰다. 데몬으로 두어 이 스레드가 종료를 붙잡지 않게 한다.
     */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coupon-seq-rebuild");
        thread.setDaemon(true);
        return thread;
    });

    private final CouponSeqRebuilder rebuilder;

    /**
     * 요청 스레드가 부른다. 하는 일이 집합에 넣는 것뿐이라 발급 경로에 지는 값이 거의 없다.
     *
     * <p>이 호출은 손실을 단정하지 않는다. 관리자가 아직 안 연 이벤트도 같은 자리로 오므로,
     * 그 둘을 가르는 것은 {@link CouponSeqRebuilder} 가 DB 를 보고 한다.
     */
    public void suspect(long couponId) {
        if (!inProgress.add(couponId)) {
            return;
        }
        try {
            worker.execute(() -> {
                try {
                    rebuilder.rebuildIfLost(couponId);
                } catch (RuntimeException e) {
                    // 삼키면 안 되지만 이 스레드를 죽여서도 안 된다. 다음 요청이 다시 띄운다
                    log.error("event=COUPON_SEQ_REBUILD_FAILED couponId={}", couponId, e);
                } finally {
                    inProgress.remove(couponId);
                }
            });
        } catch (RejectedExecutionException e) {
            // 종료 중이다. 표시를 남기면 다음 기동에서도 안 뜨므로 지운다
            inProgress.remove(couponId);
        }
    }

    @PreDestroy
    void stop() {
        worker.shutdownNow();
    }
}
