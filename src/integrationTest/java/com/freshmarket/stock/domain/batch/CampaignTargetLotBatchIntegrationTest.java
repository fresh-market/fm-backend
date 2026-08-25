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
import com.freshmarket.stock.domain.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
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
 * 선정 로직(소진율 정렬, 하위 20% 컷, 상위 3건) 자체는 CampaignTargetLotBatchTest 가
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
@Sql("/sql/product-test-supplier.sql")
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

    private static final Long SUPPLIER_ID = 999999L;
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

    @Test
    void 소진율_하위_로트가_상위_3건_순위대로_저장된다() {
        // given — 15건, 하위 20%(ceil(15*0.2)=3) 가 TARGET_COUNT(3) 과 정확히 일치.
        // 소진량을 1~15 로 달리해 소진율을 전부 다르게 만든다. 소진량이 적을수록(=안 팔릴수록)
        // 소진율이 낮아 대상 우선순위가 높다. 재고는 1000 이라 어느 쪽도 30 미만으로 안 떨어진다.
        Long lowest = saveLot("l1", TODAY.plusDays(5), 1000, 1);   // 소진율 0.0010, 1순위
        Long second = saveLot("l2", TODAY.plusDays(5), 1000, 2);   // 소진율 0.0020, 2순위
        Long third = saveLot("l3", TODAY.plusDays(5), 1000, 3);    // 소진율 0.0030, 3순위
        for (int i = 4; i <= 15; i++) {
            saveLot("l" + i, TODAY.plusDays(5), 1000, i);
        }

        // when
        campaignTargetLotBatch.run();

        // then
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(lowest);
        assertThat(saved.get(0).getTargetRank()).isEqualTo(1);
        assertThat(saved.get(0).getTurnoverRate()).isEqualByComparingTo(new BigDecimal("0.0010"));
        assertThat(saved.get(0).getIssuableQty()).isEqualTo(999);
        assertThat(saved.get(1).getStockLotId()).isEqualTo(second);
        assertThat(saved.get(1).getTargetRank()).isEqualTo(2);
        assertThat(saved.get(2).getStockLotId()).isEqualTo(third);
        assertThat(saved.get(2).getTargetRank()).isEqualTo(3);
    }

    @Test
    void 소비기한이_D10_경계보다_하루_늦으면_대상에서_빠진다() {
        // given — 경계값(D+10)은 포함, 그 하루 뒤(D+11)는 제외. 소진량 50 이라 잔여재고 50으로
        // 하한(30)에 넉넉히 걸쳐, 이 테스트가 재고 필터가 아니라 날짜 필터만 보게 한다.
        Long withinBoundary = saveLot("경계안", TODAY.plusDays(10), 100, 50);
        saveLot("경계밖", TODAY.plusDays(11), 100, 50);

        // when
        campaignTargetLotBatch.run();

        // then — 후보가 경계 안 로트 하나뿐이라 하위 20%(ceil(1*0.2)=1)에 그 하나만 든다
        List<CampaignTargetLot> saved = campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getStockLotId()).isEqualTo(withinBoundary);
    }

    @Test
    void 잔여재고가_30_미만이면_대상에서_빠진다() {
        // given — 소진율은 0.71 로 낮지 않지만(하위 20% 판단과 무관하게), 잔여재고 29 로 하한 미달
        saveLot("재고부족", TODAY.plusDays(5), 100, 71);

        // when
        campaignTargetLotBatch.run();

        // then
        assertThat(campaignTargetLotRepository.findByTargetDateOrderByTargetRankAsc(TODAY)).isEmpty();
    }

    @Test
    void 같은_기준일에_재실행하면_이전_대상을_지우고_다시_확정한다() {
        // given
        Long lotId = saveLot("재실행", TODAY.plusDays(5), 100, 50);

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
