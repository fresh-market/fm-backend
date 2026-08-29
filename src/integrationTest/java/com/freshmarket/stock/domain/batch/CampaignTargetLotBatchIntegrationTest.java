package com.freshmarket.stock.domain.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import com.freshmarket.stock.domain.entity.CampaignTargetLot;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.entity.DisposalReason;
import com.freshmarket.stock.domain.entity.StockMovement;
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/*
 * 자정 배치가 실제 DB 로 후보를 조회하고, 필터·순위·저장까지 전체 파이프라인을 검증한다.
 * 선정 로직(소진율 정렬, 하위 10% 컷) 자체는 CampaignTargetLotBatchTest 가
 * 모킹으로 이미 촘촘히 본다 — 여기서는 실제 QueryDSL 조회 조건(D-10 경계)과
 * 재고 차감(decreaseAvailableQty), 재실행 시 덮어쓰기까지 DB 를 태워 확인한다.
 *
 * @Profile("batch") 컴포넌트라 batch 프로필을 함께 켜야 빈이 뜬다. integrationTest 프로필을
 * 빼면 application-integrationTest.yml 이 읽히지 않아 컨텍스트 자체가 못 뜬다 — 두 프로필을
 * 같이 켜야 한다(@ActiveProfiles 는 spring.profiles.active 시스템 프로퍼티를 덮어쓴다).
 */
@SpringBootTest
@ActiveProfiles({"integrationTest", "batch"})
@Transactional
@Sql({"/sql/product-test-supplier.sql", "/sql/stock-test-admin.sql"})
@Testcontainers
class CampaignTargetLotBatchIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private CampaignTargetLotBatch campaignTargetLotBatch;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockLotRepository stockLotRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Autowired
    private StockMovementRepository stockMovementRepository;

    private static final Long SUPPLIER_ID = 999999L;
    private static final Long ADMIN_ID = 999999L;
    private static final LocalDate TODAY = LocalDate.now();

    private Long fruitCategoryId() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("과일"))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    // 로트 하나를 만들고, 소진 시뮬레이션을 위해 필요하면 availableQty 를 실제 운영 경로(조건부 UPDATE)로 줄인다.
    // StockLot 에는 줄이는 공개 메서드가 없다 — restore() 는 늘리는 것뿐이다(예약 해제 전용).
    // reserve() 가 실제로 쓰는 StockLotRepository.decreaseAvailableQty 를 그대로 재사용한다.
    private Long saveLot(String name, LocalDate expiryDate, int initialQty, int soldQty) {
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-" + name, "P-" + name, name, categoryId, SUPPLIER_ID, StorageType.COLD, 10));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 10000));
        StockLot lot = stockLotRepository.save(StockLot.register(
                "lot-req-" + name, option.getId(), TODAY.minusDays(1), expiryDate, initialQty));
        if (soldQty > 0) {
            stockLotRepository.decreaseAvailableQty(lot.getId(), soldQty);
        }
        return lot.getId();
    }

    /*
     * 폐기 이력을 남긴다. saveLot 의 soldQty 가 예약(RESERVE)으로 재고를 줄이는 것과 달리,
     * 폐기는 사유에 따라 재고를 줄이기도 하고(DAMAGED/EXPIRED) 안 줄이기도 해서(RETURNED)
     * 호출부가 qtyBefore/qtyAfter 를 직접 준다 — chk_movement_qty 가 그 관계를 강제한다.
     */
    private void saveDisposal(Long lotId, int quantity, DisposalReason reason, int qtyBefore, int qtyAfter) {
        stockMovementRepository.save(StockMovement.dispose(
                "disposal-req-" + lotId, lotId, quantity, qtyBefore, qtyAfter, ADMIN_ID, reason, "테스트 폐기"));
    }

    @Test
    void 소진율_하위_10퍼센트가_순위대로_저장된다() {
        // given — 15건, 하위 10% = ceil(15/10) = 2건이 대상이다.
        // 소진량을 1~15 로 달리해 소진율을 전부 다르게 만든다. 소진량이 적을수록(=안 팔릴수록)
        // 소진율이 낮아 대상 우선순위가 높다. 재고는 1000 이라 어느 쪽도 30 미만으로 안 떨어진다.
        Long lowest = saveLot("l1", TODAY.plusDays(12), 1000, 1);   // 소진율 0.0010, 1순위
        Long second = saveLot("l2", TODAY.plusDays(12), 1000, 2);   // 소진율 0.0020, 2순위
        // 나머지 13건. 소진율만 위 두 건보다 높으면 되므로 정확한 값은 결과에 영향 없다 (UT-3-04)
        saveLot("l3", TODAY.plusDays(12), 1000, 3);
        saveLot("l4", TODAY.plusDays(12), 1000, 4);
        saveLot("l5", TODAY.plusDays(12), 1000, 5);
        saveLot("l6", TODAY.plusDays(12), 1000, 6);
        saveLot("l7", TODAY.plusDays(12), 1000, 7);
        saveLot("l8", TODAY.plusDays(12), 1000, 8);
        saveLot("l9", TODAY.plusDays(12), 1000, 9);
        saveLot("l10", TODAY.plusDays(12), 1000, 10);
        saveLot("l11", TODAY.plusDays(12), 1000, 11);
        saveLot("l12", TODAY.plusDays(12), 1000, 12);
        saveLot("l13", TODAY.plusDays(12), 1000, 13);
        saveLot("l14", TODAY.plusDays(12), 1000, 14);
        saveLot("l15", TODAY.plusDays(12), 1000, 15);

        // when
        campaignTargetLotBatch.run();

        // then
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(lowest);
        assertThat(saved.get(0).getTargetRank()).isEqualTo(1);
        assertThat(saved.get(0).getTurnoverRate()).isEqualByComparingTo(new BigDecimal("0.0010"));
        assertThat(saved.get(0).getIssuableQty()).isEqualTo(999);
        assertThat(saved.get(1).getStockLotId()).isEqualTo(second);
        assertThat(saved.get(1).getTargetRank()).isEqualTo(2);
    }

    @Test
    void 판매_마감_기한이_지난_로트는_대상에서_빠진다() {
        // given — D+10 이 판매 마감 기한선이다. 그보다 소비기한이 가까우면 이미 팔 수 없어
        // 쿠폰을 붙여도 쓸 수가 없다. 경계(D+10)는 포함, 하루 앞(D+9)은 제외다.
        // 소진량 50 이라 잔여재고 50 으로, 이 테스트가 재고 필터가 아니라 날짜 필터만 보게 한다.
        Long onBoundary = saveLot("경계안", TODAY.plusDays(10), 100, 50);
        saveLot("마감지남", TODAY.plusDays(9), 100, 50);

        // when
        campaignTargetLotBatch.run();

        // then — 후보가 경계 안 로트 하나뿐이라 하위 10%(ceil(1/10)=1)에 그 하나만 든다
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(onBoundary);
    }

    @Test
    void 아직_임박하지_않은_로트는_대상에서_빠진다() {
        // given — D+13 이 임박 시작선이다. 경계(D+13)는 포함, 하루 뒤(D+14)는 아직 임박이 아니다
        Long onBoundary = saveLot("경계안", TODAY.plusDays(13), 100, 50);
        saveLot("아직", TODAY.plusDays(14), 100, 50);

        // when
        campaignTargetLotBatch.run();

        // then
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(onBoundary);
    }

    @Test
    void 잔여재고가_30_미만이면_대상에서_빠진다() {
        // given — 소진율은 0.71 로 낮지 않지만(하위 10% 판단과 무관하게), 잔여재고 29 로 하한 미달
        saveLot("재고부족", TODAY.plusDays(12), 100, 71);

        // when
        campaignTargetLotBatch.run();

        // then
        assertThat(campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY)).isEmpty();
    }

    /*
     * RETURNED 는 available_qty 를 줄이지 않는 폐기다(AdminLotService.dispose, chk_movement_qty).
     * 소진율 분모(입고 - 폐기)에서 빼면 분모가 잔여재고보다 작아져 소진율이 음수가 되고,
     * TurnoverRateCalculator 의 입력 검증에 걸려 배치 전체가 예외로 죽는다.
     */
    @Test
    void 회수품_폐기는_소진율_계산에서_빼지_않는다() {
        // given — 입고 100, RETURNED 폐기 20, 판매 0. 잔여재고는 100 그대로다
        Long lotId = saveLot("회수품", TODAY.plusDays(12), 100, 0);
        saveDisposal(lotId, 20, DisposalReason.RETURNED, 100, 100);

        // when — 예외 없이 끝나야 한다
        campaignTargetLotBatch.run();

        // then — 한 개도 안 팔렸으므로 소진율 0
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(lotId);
        assertThat(saved.get(0).getTurnoverRate()).isEqualByComparingTo(new BigDecimal("0.0000"));
    }

    @Test
    void 회수품이_아닌_폐기는_팔린_것으로_세지_않는다() {
        // given — 입고 100, DAMAGED 폐기 30(잔여 70 으로 줄어든다), 판매 0
        //   교정 전: (100-70)/100 = 0.30  ← 30% 팔린 것처럼 보인다
        //   교정 후: (70-70)/70   = 0.00  ← 실제로 0% 다
        Long lotId = saveLot("손상품", TODAY.plusDays(12), 100, 30);
        saveDisposal(lotId, 30, DisposalReason.DAMAGED, 100, 70);

        // when
        campaignTargetLotBatch.run();

        // then
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getTurnoverRate()).isEqualByComparingTo(new BigDecimal("0.0000"));
    }

    @Test
    void 같은_기준일에_재실행하면_이전_대상을_지우고_다시_확정한다() {
        // given
        Long lotId = saveLot("재실행", TODAY.plusDays(12), 100, 50);

        // when — 두 번 연속 실행. deleteByTargetDate 가 @Modifying 벌크 삭제라 호출 즉시 DB 에
        // 반영되므로, 같은 트랜잭션 안에서 재실행해도 뒤이은 save() 의 INSERT 와 순서가 꼬이지 않는다.
        campaignTargetLotBatch.run();
        campaignTargetLotBatch.run();

        // then — 중복 누적되지 않고 오늘자 대상이 한 건만 남는다 (deleteByTargetDate)
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(lotId);
    }
}
