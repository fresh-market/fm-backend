package com.freshmarket.stock.internal.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.freshmarket.IntegrationTestSupport;
import com.freshmarket.product.internal.entity.Product;
import com.freshmarket.product.internal.entity.ProductOption;
import com.freshmarket.product.internal.entity.StorageType;
import com.freshmarket.product.internal.repository.CategoryRepository;
import com.freshmarket.product.internal.repository.ProductOptionRepository;
import com.freshmarket.product.internal.repository.ProductRepository;
import com.freshmarket.stock.internal.ExpiringSoonPolicy;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.entity.StockLot;
import com.freshmarket.stock.internal.exception.StockErrorCode;
import com.freshmarket.stock.internal.exception.StockException;
import com.freshmarket.stock.internal.repository.CampaignRebuildLockRepository;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.internal.repository.StockLotRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.support.TransactionTemplate;

/*
 * 재확정이 겹칠 때 무엇이 막아 주는지 본다.
 *
 * 전에는 이 자리를 uk_campaign_target_date_lot 에 맡겼는데 그것으로는 부족했다. 확정이
 * "삭제 → 계산 → 삽입" 이라 제약이 걸리는 지점까지 가기 전에 락이 얽혀, 겹친 쪽은 유니크
 * 위반이 아니라 데드락(CannotAcquireLockException)으로 떨어졌다 — 삭제가 잡는 구간(갭) 락끼리는
 * 서로 충돌하지 않아 둘 다 통과한 뒤 삽입에서 서로를 기다렸기 때문이다. 전용 핸들러가 없어
 * 그 데드락은 409 가 아니라 500 으로 나갔다.
 *
 * 지금은 계산 앞에서 campaign_rebuild_lock 의 행 하나를 NOWAIT 로 잡는다. 자원이 하나라 기다림이
 * 원을 그릴 수 없고, 못 잡은 쪽은 매달리지 않고 그 자리에서 409 로 떨어진다.
 *
 * 그래서 이 시험이 잠그는 것은 셋이다 — 못 잡은 쪽이 (1) 기다리지 않고 거절되는가,
 * (2) 계산은커녕 삭제도 시작하지 않는가, (3) 더 이상 데드락으로 떨어지지 않는가.
 *
 * "다른 인스턴스가 확정 중" 은 시험 스레드가 잠금 행을 쥔 트랜잭션을 열어 둔 채 만든다.
 * 시계에 기대지 않으므로 재현이 기계 속도를 안 탄다.
 *
 * 트랜잭션을 클래스에 걸지 않는다. 잠금이 트랜잭션 경계에 묶여 있어, 시험 트랜잭션 안에서 돌면
 * 잡는 쪽과 잡히는 쪽이 같은 트랜잭션이 되어 재현 자체가 성립하지 않는다.
 */
@SpringBootTest
@Sql({"/sql/product-test-supplier.sql", "/sql/stock-test-admin.sql"})
class CampaignTargetLotRebuildConcurrencyIntegrationTest extends IntegrationTestSupport {

    private static final Long SUPPLIER_ID = 999999L;
    private static final BigDecimal RATE = new BigDecimal("0.0100");

    /*
     * 소비기한을 일부러 멀리 둔다. 이 시험은 확정 결과의 내용이 아니라 겹침만 보므로 후보가
     * 0 건인 편이 낫고, 임박 구간(D-13 ~ D-10)에 걸쳐 두면 컨테이너를 함께 쓰는 다른 시험의
     * 후보로도 잡힌다. 커밋되는 데이터라 남는 동안 계속 보인다.
     */
    private static final int FAR_EXPIRY_DAYS = 365;

    @Autowired
    private CampaignTargetLotRebuildService rebuildService;

    @Autowired
    private CampaignRebuildLockRepository campaignRebuildLockRepository;

    @Autowired
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Autowired
    private StockLotRepository stockLotRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private TransactionTemplate txTemplate;

    @Autowired
    private Clock clock;

    private final List<Long> createdLotIds = new ArrayList<>();

    // 확정본이 남으면 다음 회차의 삭제 대상이 달라진다. 로트도 커밋되므로 함께 지운다
    @AfterEach
    void tearDown() {
        txTemplate.executeWithoutResult(status -> {
            campaignTargetLotRepository.deleteByTargetDate(today());
            createdLotIds.forEach(stockLotRepository::deleteById);
        });
        createdLotIds.clear();
    }

    // 배치와 조회가 보는 기준일. 호스트 시간대가 무엇이든 한국 날짜를 준다
    private LocalDate today() {
        return ExpiringSoonPolicy.businessToday(clock);
    }

    private Long saveLot(String name) {
        return txTemplate.execute(status -> {
            Long categoryId = categoryRepository.findAll().stream()
                    .filter(c -> c.getName().equals("과일"))
                    .findFirst()
                    .orElseThrow()
                    .getId();
            Product product = productRepository.save(Product.register(
                    "req-" + name, "P-" + name, name, categoryId, SUPPLIER_ID, StorageType.COLD, 10));
            ProductOption option = productOptionRepository.save(
                    ProductOption.register(product.getId(), "1kg", 10000));
            StockLot lot = stockLotRepository.save(StockLot.register(
                    "lot-req-" + name, option.getId(),
                    today().minusDays(1), today().plusDays(FAR_EXPIRY_DAYS), 1000));
            createdLotIds.add(lot.getId());
            return lot.getId();
        });
    }

    // 지워졌는지 살아남았는지로 "삭제까지 갔는가" 를 재는 표식이다
    private void seedConfirmedRow(Long lotId) {
        txTemplate.executeWithoutResult(status -> campaignTargetLotRepository.save(
                CampaignTargetLot.register(today(), lotId, RATE, 100, 1)));
    }

    /*
     * 다른 인스턴스가 확정 중인 상태를 만든다. 잠금 행을 쥔 트랜잭션을 열어 둔 채 두고,
     * 돌려주는 래치를 내리면 그 트랜잭션이 끝나면서 잠금이 풀린다.
     */
    private CountDownLatch otherInstanceRebuilding(ExecutorService pool) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        pool.execute(() -> txTemplate.executeWithoutResult(status -> {
            campaignRebuildLockRepository.lockForRebuild();
            locked.countDown();
            awaitQuietly(release);
        }));

        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return release;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금을 쥔 채로 시간이 다 됐다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /*
     * 예전에는 여기서 데드락이 났다. 지금은 잠금 단계에서 갈리므로 기다리지도, 교착하지도 않는다.
     *
     * StockException 이라는 것이 곧 409 로 나간다는 뜻이고, PessimisticLockingFailureException 이
     * 아니라는 것이 곧 날 것의 DB 예외가 새어나가 500 이 되지 않는다는 뜻이다.
     */
    @Test
    void 다른_인스턴스가_확정_중이면_기다리지_않고_거절한다() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CountDownLatch release = otherInstanceRebuilding(pool);
            try {
                assertThatThrownBy(() -> rebuildService.rebuild())
                        .isInstanceOf(StockException.class)
                        .isNotInstanceOf(PessimisticLockingFailureException.class)
                        .hasMessageContaining(StockErrorCode.CAMPAIGN_REBUILD_IN_PROGRESS.getMessage());
            } finally {
                release.countDown();
            }
        }
    }

    /*
     * 잠금이 계산 앞에 있다는 것을 결과로 확인한다.
     * 미리 심어 둔 확정본이 그대로면 deleteByTargetDate 까지 가지 않았다는 뜻이다.
     */
    @Test
    void 잠금을_못_잡으면_기존_확정본을_지우지도_않는다() throws Exception {
        Long lotId = saveLot("표식");
        seedConfirmedRow(lotId);

        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CountDownLatch release = otherInstanceRebuilding(pool);
            try {
                assertThatThrownBy(() -> rebuildService.rebuild()).isInstanceOf(StockException.class);

                List<CampaignTargetLot> remaining =
                        campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(today());
                assertThat(remaining).hasSize(1);
                assertThat(remaining.get(0).getStockLotId()).isEqualTo(lotId);
            } finally {
                release.countDown();
            }
        }
    }

    // 잠금을 푸는 코드가 없다. 트랜잭션이 끝나면 저절로 풀리는지 확인한다
    @Test
    void 앞_회차가_끝나면_다음_확정이_통과한다() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
            CountDownLatch release = otherInstanceRebuilding(pool);
            assertThatThrownBy(() -> rebuildService.rebuild()).isInstanceOf(StockException.class);
            release.countDown();
        }

        // 후보가 없는 날이라 0 이 정상이다. 예외 없이 끝나는 것이 요지다
        assertThat(rebuildService.rebuild()).isZero();
    }

    /*
     * 예전 재현과 같은 모양으로 겹쳐 부른다. 그때는 한쪽이 데드락으로 떨어졌다.
     *
     * 지금은 둘 중 하나만 나올 수 있다 — 나란히 통과하거나(앞 회차가 이미 끝났다),
     * 한쪽이 409 로 떨어지거나. 어느 쪽이든 데드락은 나오지 않는다.
     *
     * 어느 갈래를 타는지가 타이밍에 달려 있어 회차를 여러 번 돌린다. 단정이 두 갈래에서 모두
     * 성립하므로 어느 쪽으로 갈리든 시험이 깜빡이지 않는다.
     */
    @Test
    void 겹쳐_불러도_더_이상_데드락이_나지_않는다() throws Exception {
        int rounds = 5;
        List<RuntimeException> failures = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < rounds; i++) {
                CountDownLatch bothReady = new CountDownLatch(2);
                CountDownLatch done = new CountDownLatch(2);

                for (int j = 0; j < 2; j++) {
                    pool.execute(() -> {
                        bothReady.countDown();
                        awaitQuietly(bothReady);
                        try {
                            rebuildService.rebuild();
                        } catch (RuntimeException e) {
                            failures.add(e);
                        } finally {
                            done.countDown();
                        }
                    });
                }
                assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            }
        }

        // 떨어진 것이 있다면 전부 "지금 돌고 있다"(409) 여야 한다. 데드락은 한 건도 없어야 한다
        assertThat(failures).allSatisfy(failure -> assertThat(failure)
                .isInstanceOf(StockException.class)
                .isNotInstanceOf(PessimisticLockingFailureException.class));
        assertThat(failures).noneSatisfy(failure ->
                assertThat(failure).hasStackTraceContaining("Deadlock found"));
    }
}
