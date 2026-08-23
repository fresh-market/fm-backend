package com.freshmarket.product.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.freshmarket.product.ProductApi;
import com.freshmarket.product.ProductOptionInfo;
import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// ProductApi 가 다른 도메인에 정확한 join 결과를 돌려주는지 실제 DB 로 검증한다
@SpringBootTest
@Transactional
@Sql("/sql/product-test-supplier.sql")
@Testcontainers
class ProductApiImplIntegrationTest {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Autowired
    private ProductApi productApi;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

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

    @Test
    void 존재하는_옵션의_정보를_join해서_가져온다() {
        // given
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-1", "P-감귤", "감귤", categoryId, SUPPLIER_ID, StorageType.COLD, 3));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 12900));

        // when
        Optional<ProductOptionInfo> result = productApi.findOptionInfo(option.getId());

        // then
        assertThat(result).isPresent();
        assertThat(result.get().productId()).isEqualTo(product.getId());
        assertThat(result.get().categoryId()).isEqualTo(categoryId);
        assertThat(result.get().productName()).isEqualTo("감귤");
        assertThat(result.get().optionName()).isEqualTo("1kg");
        assertThat(result.get().price()).isEqualTo(12900);
        assertThat(result.get().purchasable()).isTrue();
        assertThat(result.get().saleAvailableDaysFromExpiry()).isEqualTo(3);
    }

    @Test
    void 존재하지_않는_옵션은_빈_값을_준다() {
        // when
        Optional<ProductOptionInfo> result = productApi.findOptionInfo(999999L);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void 품절_옵션은_구매불가로_판정한다() {
        // given
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-2", "P-복숭아", "복숭아", categoryId, SUPPLIER_ID, StorageType.COLD, 3));
        ProductOption option = productOptionRepository.save(
                ProductOption.register(product.getId(), "3kg", 20000));
        ReflectionTestUtils.setField(option, "saleStatus", SaleStatus.SOLD_OUT);
        productOptionRepository.save(option);

        // when
        Optional<ProductOptionInfo> result = productApi.findOptionInfo(option.getId());

        // then
        assertThat(result).isPresent();
        assertThat(result.get().purchasable()).isFalse();
    }

    @Test
    void 여러_옵션을_한_번에_조회한다() {
        // given
        Long categoryId = fruitCategoryId();
        Product product = productRepository.save(Product.register(
                "req-3", "P-사과", "사과", categoryId, SUPPLIER_ID, StorageType.COLD, 3));
        ProductOption option1 = productOptionRepository.save(
                ProductOption.register(product.getId(), "1kg", 5000));
        ProductOption option2 = productOptionRepository.save(
                ProductOption.register(product.getId(), "3kg", 13000));

        // when — 존재하지 않는 id 하나를 섞어도 나머지는 정상 반환된다
        List<ProductOptionInfo> result = productApi.findOptionInfos(
                List.of(option1.getId(), option2.getId(), 999999L));

        // then
        assertThat(result).hasSize(2);
    }

    @Test
    void 빈_목록을_주면_빈_결과를_준다() {
        // when
        List<ProductOptionInfo> result = productApi.findOptionInfos(List.of());

        // then
        assertThat(result).isEmpty();
    }
}