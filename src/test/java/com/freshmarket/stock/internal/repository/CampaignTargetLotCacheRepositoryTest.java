package com.freshmarket.stock.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.stock.internal.dto.ExpiringSoonResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * 로컬 캐시의 키 분리와 적재 규칙을 검증한다.
 * 캐시 자체는 Caffeine 이 보장하므로, 여기서는 이 클래스가 정한 규칙만 본다 —
 * 어떤 요청을 서로 다른 항목으로 볼 것인가, 무엇을 담지 않을 것인가,
 * 그리고 같은 키가 동시에 미스했을 때 원본 조회가 몇 번 도는가.
 */
class CampaignTargetLotCacheRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);
    private static final Long VERSION = 100L;

    private CampaignTargetLotCacheRepository repository;

    @BeforeEach
    void setUp() {
        repository = new CampaignTargetLotCacheRepository();
    }

    private CursorPageResponse<ExpiringSoonResponse> response(String productName) {
        return CursorPageResponse.of(
                List.of(new ExpiringSoonResponse(12L, productName, 31L, "1kg", 12900)), null);
    }

    private CursorPageResponse<ExpiringSoonResponse> empty() {
        return CursorPageResponse.of(List.of(), null);
    }

    @Test
    void 담아두면_같은_조건으로_다시_꺼낼_수_있다() {
        repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("감귤"));

        // 두 번째 호출은 loader 를 안 타고 담아둔 것을 준다
        CursorPageResponse<ExpiringSoonResponse> found =
                repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("불려서는 안 됨"));

        assertThat(found.items().get(0).productName()).isEqualTo("감귤");
    }

    /*
     * 기준일이 키 앞에 있어 자정이 지나면 키가 통째로 달라진다.
     * 지난 날짜의 확정본을 오늘 것으로 잘못 내보낼 경로가 없다는 뜻이다.
     */
    @Test
    void 기준일이_다르면_다른_항목이다() {
        repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("어제 감귤"));

        CursorPageResponse<ExpiringSoonResponse> tomorrow = repository.getOrLoad(
                TODAY.plusDays(1), VERSION, null, null, 20, () -> response("오늘 감귤"));

        assertThat(tomorrow.items().get(0).productName()).isEqualTo("오늘 감귤");
    }

    /*
     * 관리자가 재확정하면 그날 행이 새로 만들어져 확정본 버전이 바뀐다.
     * 키가 달라져야 옛 응답을 다시 내보내지 않는다 — 로컬 캐시라 인스턴스별로 비울 방법이
     * 없어 무효화 대신 이 키 분리로 푼다.
     */
    @Test
    void 확정본_버전이_다르면_다른_항목이다() {
        repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("재확정 전"));

        CursorPageResponse<ExpiringSoonResponse> after = repository.getOrLoad(
                TODAY, VERSION + 1, null, null, 20, () -> response("재확정 후"));

        assertThat(after.items().get(0).productName()).isEqualTo("재확정 후");
    }

    @Test
    void 카테고리가_다르면_다른_항목이다() {
        repository.getOrLoad(TODAY, VERSION, 4L, null, 20, () -> response("과일"));

        CursorPageResponse<ExpiringSoonResponse> other =
                repository.getOrLoad(TODAY, VERSION, 5L, null, 20, () -> response("채소"));

        assertThat(other.items().get(0).productName()).isEqualTo("채소");
    }

    @Test
    void 페이지_토큰이_다르면_다른_항목이다() {
        repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("첫 페이지"));

        CursorPageResponse<ExpiringSoonResponse> next =
                repository.getOrLoad(TODAY, VERSION, null, "cursor-token", 20, () -> response("둘째 페이지"));

        assertThat(next.items().get(0).productName()).isEqualTo("둘째 페이지");
    }

    @Test
    void 페이지_크기가_다르면_다른_항목이다() {
        repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("20건"));

        CursorPageResponse<ExpiringSoonResponse> smaller =
                repository.getOrLoad(TODAY, VERSION, null, null, 10, () -> response("10건"));

        assertThat(smaller.items().get(0).productName()).isEqualTo("10건");
    }

    /*
     * 자정 직후 배치가 아직 커밋하기 전에 들어온 요청이 빈 목록을 굳혀버리면,
     * 그날 대상이 확정된 뒤에도 계속 빈 응답을 주게 된다.
     */
    @Test
    void 빈_결과는_담지_않는다() {
        CursorPageResponse<ExpiringSoonResponse> first =
                repository.getOrLoad(TODAY, VERSION, null, null, 20, this::empty);
        assertThat(first.items()).isEmpty();

        // 담기지 않았으므로 다음 호출은 loader 를 다시 탄다
        CursorPageResponse<ExpiringSoonResponse> second =
                repository.getOrLoad(TODAY, VERSION, null, null, 20, () -> response("배치가 커밋한 뒤"));

        assertThat(second.items().get(0).productName()).isEqualTo("배치가 커밋한 뒤");
    }

    /*
     * 같은 키가 동시에 미스해도 원본 조회는 한 번만 돈다.
     *
     * 이것이 없으면 캐시가 가장 필요한 순간에 무력해진다 — 쿠폰 오픈 직후 캐시가 비어 있을 때
     * 들어온 요청이 전부 DB 로 내려간다(cache stampede).
     *
     * 경합 창을 시계가 아니라 래치로 연다. loader 안에서 잠들면 잠든 시간이 곧 창의 길이가
     * 되는데, 그 길이는 기계마다 다르게 먹힌다 — 느린 CI 에서는 창이 닫히기 전에 못 모이고
     * 빠른 기계에서는 쓸데없이 오래 기다린다. 여기서는 첫 조회를 래치로 붙잡아 두어
     * 나머지 열아홉이 도착할 때까지 창이 확실히 열려 있게 한다.
     *
     * 두 번째 조회가 들어오는지는 "안 들어옴" 을 기다려서 본다. 들어오면 그 자리에서 바로
     * 실패하고, 안 들어오면 창을 다 기다린 뒤 통과한다. 조회와 적재를 나눠 부르던 예전
     * 방식이면 열아홉이 그대로 loader 로 들어와 즉시 걸린다.
     */
    @Test
    void 같은_키가_동시에_미스해도_원본_조회는_한_번만_돈다() throws Exception {
        int threads = 20;
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch firstLoadEntered = new CountDownLatch(1);
        CountDownLatch secondLoadEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstLoad = new CountDownLatch(1);
        CountDownLatch atCallSite = new CountDownLatch(threads);
        CountDownLatch done = new CountDownLatch(threads);

        Supplier<CursorPageResponse<ExpiringSoonResponse>> loader = () -> {
            if (loaderCalls.incrementAndGet() == 1) {
                firstLoadEntered.countDown();
            } else {
                secondLoadEntered.countDown();
            }
            awaitQuietly(releaseFirstLoad);   // 원본 조회가 도는 동안 경합 창을 열어 둔다
            return response("감귤");
        };

        // ExecutorService 는 AutoCloseable 이다. close() 가 종료를 걸고 작업이 끝나기를 기다린다
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                pool.execute(() -> {
                    try {
                        atCallSite.countDown();
                        repository.getOrLoad(TODAY, VERSION, null, null, 20, loader);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(firstLoadEntered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(atCallSite.await(5, TimeUnit.SECONDS)).isTrue();

            // 창이 열려 있는 동안 두 번째 조회가 들어오면 stampede 다
            assertThat(secondLoadEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

            releaseFirstLoad.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(loaderCalls.get()).isEqualTo(1);
    }

    // 래치를 기다린다. Supplier 안에서 부르므로 검사 예외를 밖으로 낼 수 없다
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("경합 창이 열린 채로 시간이 다 됐다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
