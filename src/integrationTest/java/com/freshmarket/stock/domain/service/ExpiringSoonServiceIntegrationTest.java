package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import com.freshmarket.stock.domain.dto.ExpiringSoonResponse;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// 소비기한 임박 판정(소비기한 - saleAvailableDaysFromExpiry <= 오늘 + withinDays)이
// 실제 DB 로 정확히 계산되는지 검증한다
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
    private CategoryRepository categoryRepository;

    private static final Long SUPPLIER_ID = 999999L;

    private Long fruitCategoryId() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("과일"))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private Long saveOptionWithLot(String name, int saleAvailableDays, LocalDate expiryDate) {
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-" + name, "P-" + name, name, categoryId, SUPPLIER_ID,
                StorageType.COLD, saleAvailableDays));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 10000));
        stockLotRepository.save(StockLot.register(
                "lot-req-" + name, option.getId(), LocalDate.now().minusDays(1), expiryDate, 100));
        return option.getId();
    }

    @Test
    void 판매_마감_기한이_임박_범위_안이면_조회된다() {
        // given — 소비기한 오늘+13일, saleAvailableDaysFromExpiry=10
        //         판매 마감 기한 = 오늘+13 - 10 = 오늘+3, withinDays 기본값 3 이내라 임박
        saveOptionWithLot("감귤", 10, LocalDate.now().plusDays(13));

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.get(0).productName()).isEqualTo("감귤");
    }

    @Test
    void 판매_마감_기한이_임박_범위_밖이면_조회되지_않는다() {
        // given — 소비기한 오늘+30일, saleAvailableDaysFromExpiry=3
        //         판매 마감 기한 = 오늘+30 - 3 = 오늘+27, withinDays 기본값 3 을 훨씬 넘음
        saveOptionWithLot("복숭아", 3, LocalDate.now().plusDays(30));

        // when
        List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void withinDays를_직접_지정하면_그_기준으로_판정한다() {
        // given — 판매 마감 기한이 오늘+8일인 상품. 기본값(3)으로는 안 걸리지만 10으로는 걸린다
        saveOptionWithLot("사과", 5, LocalDate.now().plusDays(13));

        // when
        List<ExpiringSoonResponse> withDefaultDays = expiringSoonService.getExpiringSoonProducts(3, null);
        List<ExpiringSoonResponse> withTenDays = expiringSoonService.getExpiringSoonProducts(10, null);

        // then
        assertThat(withDefaultDays).isEmpty();
        assertThat(withTenDays).hasSize(1);
    }

    @Test
    void 카테고리로_거를_수_있다() {
        // given
        Long categoryId = fruitCategoryId();
        saveOptionWithLot("배", 10, LocalDate.now().plusDays(13));

        // when
        List<ExpiringSoonResponse> matched =
        		expiringSoonService.getExpiringSoonProducts(3, categoryId);
        List<ExpiringSoonResponse> unmatched =
        		expiringSoonService.getExpiringSoonProducts(3, 999999L);

        // then
        assertThat(matched).hasSize(1);
        assertThat(unmatched).isEmpty();
    }

    @Test
    void 임박_상품이_없으면_빈_목록을_준다() {
        // when
    	List<ExpiringSoonResponse> result = expiringSoonService.getExpiringSoonProducts(3, null);

        // then
        assertThat(result).isEmpty();
    }
}