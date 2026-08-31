package com.freshmarket.stock.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.product.internal.entity.Product;
import com.freshmarket.product.internal.entity.ProductOption;
import com.freshmarket.product.internal.entity.StorageType;
import com.freshmarket.product.internal.repository.CategoryRepository;
import com.freshmarket.product.internal.repository.ProductOptionRepository;
import com.freshmarket.product.internal.repository.ProductRepository;
import com.freshmarket.stock.internal.dto.ExpiringSoonResponse;
import com.freshmarket.stock.internal.entity.CampaignTargetLot;
import com.freshmarket.stock.internal.entity.StockLot;
import com.freshmarket.stock.internal.repository.CampaignTargetLotCacheRepository;
import com.freshmarket.stock.internal.repository.CampaignTargetLotRepository;
import com.freshmarket.stock.internal.repository.StockLotRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/*
 * 회원용 조회가 확정된 캠페인 대상(campaign_target_lot)만 읽는지, 커서 페이지네이션이
 * 실제 DB 로 정확히 도는지 검증한다.
 *
 * 임박 판정을 여기서 하지 않으므로, 테스트도 로트를 만들어 "임박해지길" 기대하지 않고
 * 캠페인 대상 행을 직접 넣어 검증한다 — 대상 선정 자체는 CampaignTargetLotBatchIntegrationTest 가 본다.
 */
@SpringBootTest
@Transactional
@Sql("/sql/product-test-supplier.sql")
@Testcontainers
class ExpiringSoonServiceIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private ExpiringSoonService expiringSoonService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private StockLotRepository stockLotRepository;

    @Autowired
    private CampaignTargetLotRepository campaignTargetLotRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private CampaignTargetLotCacheRepository campaignTargetLotCacheRepository;

    private static final Long SUPPLIER_ID = 999999L;
    private static final LocalDate TODAY = LocalDate.now();

    /*
     * 캐시는 로컬(JVM) 이라 스프링 컨텍스트와 수명을 같이한다 — 테스트마다 롤백되는 DB 와 달리
     * 앞 테스트가 담아둔 것이 그대로 남는다. 각 테스트가 자기 데이터만 보도록 비우고 시작한다.
     */
    @BeforeEach
    void clearCache() {
        campaignTargetLotCacheRepository.clear();
    }

    private Long fruitCategoryId() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("과일"))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    /*
     * 상품·옵션·로트를 만들고, 그 로트를 오늘자 캠페인 대상으로 확정해 둔다.
     * 배치를 돌리는 대신 확정 결과를 직접 넣는다 — 이 테스트가 보는 것은 조회 쪽이다.
     */
    private void saveTargetLot(String name, int targetRank) {
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-" + name, "P-" + name, name, categoryId, SUPPLIER_ID, StorageType.COLD, 10));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 10000));
        StockLot lot = stockLotRepository.save(StockLot.register(
                "lot-req-" + name, option.getId(), TODAY.minusDays(2), TODAY.plusDays(12), 100));
        campaignTargetLotRepository.save(CampaignTargetLot.register(
                TODAY, lot.getId(), new BigDecimal("0.0500"), 70, targetRank));
    }

    @Test
    void 오늘_확정된_대상이_없으면_빈_목록을_준다() {
        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).isEmpty();
    }

    @Test
    void 확정된_대상_상품을_돌려준다() {
        saveTargetLot("감귤", 1);

        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).productName()).isEqualTo("감귤");
    }

    @Test
    void 대상이_아닌_로트는_임박해도_노출되지_않는다() {
        // given — 소비기한이 임박 구간(D+12)에 있지만 campaign_target_lot 에 없는 로트
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-비대상", "P-비대상", "비대상", categoryId, SUPPLIER_ID, StorageType.COLD, 10));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 10000));
        stockLotRepository.save(StockLot.register(
                "lot-req-비대상", option.getId(), TODAY.minusDays(2), TODAY.plusDays(12), 100));

        // when
        CursorPageResponse<ExpiringSoonResponse> result =
                expiringSoonService.getExpiringSoonProducts(null, null, 20);

        // then — 확정본에 없으면 임박 여부와 무관하게 안 나온다
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 카테고리로_거를_수_있다() {
        saveTargetLot("배", 1);

        CursorPageResponse<ExpiringSoonResponse> matched =
                expiringSoonService.getExpiringSoonProducts(fruitCategoryId(), null, 20);
        CursorPageResponse<ExpiringSoonResponse> unmatched =
                expiringSoonService.getExpiringSoonProducts(999999L, null, 20);

        assertThat(matched.items()).hasSize(1);
        assertThat(unmatched.items()).isEmpty();
    }

    @Test
    void 결과가_pageSize보다_많으면_다음_페이지_토큰으로_이어서_조회된다() {
        // given — 대상 3건, pageSize 1
        saveTargetLot("감귤", 1);
        saveTargetLot("복숭아", 2);
        saveTargetLot("사과", 3);

        // when — 1페이지
        CursorPageResponse<ExpiringSoonResponse> firstPage =
                expiringSoonService.getExpiringSoonProducts(null, null, 1);

        // then
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextPageToken()).isNotNull();

        // when — 2페이지, 1페이지와 겹치지 않아야 한다
        CursorPageResponse<ExpiringSoonResponse> secondPage =
                expiringSoonService.getExpiringSoonProducts(null, firstPage.nextPageToken(), 1);

        // then
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.items().get(0).productOptionId())
                .isNotEqualTo(firstPage.items().get(0).productOptionId());
    }
}
