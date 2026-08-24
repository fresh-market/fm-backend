package com.freshmarket.product.domain;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.UploadStatus;
import com.freshmarket.product.domain.repository.ProductImageRepository;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.StorageType;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import com.freshmarket.product.domain.entity.SaleStatus;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql("/sql/product-test-supplier.sql")
// Gradle 로 돌릴 때는 integrationTest 태스크가 켜주고, IDE 에서 직접 실행할 때는 이 줄이 켠다.
class ProductApiIntegrationTest extends IntegrationTestSupport {


    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductOptionRepository productOptionRepository;

    @Autowired
    private CategoryRepository categoryRepository;
    
    @Autowired
    private ProductImageRepository productImageRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Long SUPPLIER_ID = 999999L;

    private Long fruitCategoryId() {
        return categoryRepository.findAll().stream()
                .filter(c -> c.getName().equals("과일"))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private Long saveProductWithOptions(Long categoryId, String name, int... prices) {
        Product product = productRepository.save(
                Product.register("req-" + name, "P-" + name, name, categoryId, SUPPLIER_ID,
                        StorageType.COLD, 3));
        for (int price : prices) {
            productOptionRepository.save(
                    ProductOption.register(product.getId(), price + "원대옵션", price));
        }
        return product.getId();
    }

    private void softDelete(Long productId) {
        entityManager.flush();
        entityManager.createNativeQuery(
                        "UPDATE product SET deleted_at = NOW(6), sale_status = 'OFF_SALE' "
                                + "WHERE product_id = :id")
                .setParameter("id", productId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    void 비로그인_상태에서도_상품_목록을_조회할_수_있다() throws Exception {
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "감귤", 12900);

        mockMvc.perform(get("/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void 카테고리로_필터링해_응답한다() throws Exception {
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "감귤", 12900);

        mockMvc.perform(get("/v1/products").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("감귤"))
                .andExpect(jsonPath("$.data.items[0].minPriceKrw").value(12900));
    }

    @Test
    void 삭제된_상품은_응답에서_제외된다() throws Exception {
        Long categoryId = fruitCategoryId();
        Long visibleId = saveProductWithOptions(categoryId, "감귤", 12900);
        Long deletedId = saveProductWithOptions(categoryId, "복숭아", 9900);
        softDelete(deletedId);

        mockMvc.perform(get("/v1/products").param("categoryId", String.valueOf(categoryId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].productId")
                        .value(org.hamcrest.Matchers.hasItem(visibleId.intValue())))
                .andExpect(jsonPath("$.data.items[*].productId")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(deletedId.intValue()))));
    }

    @Test
    void 가격_범위_안의_옵션이_있으면_상품이_노출된다() throws Exception {
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "감귤", 12900, 48000);

        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("minPriceKrw", "40000")
                        .param("maxPriceKrw", "60000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("감귤"))
                .andExpect(jsonPath("$.data.items[0].minPriceKrw").value(48000));
    }

    @Test
    void 범위_안의_옵션이_전혀_없으면_상품이_제외된다() throws Exception {
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "저가상품", 5000);

        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("minPriceKrw", "40000")
                        .param("maxPriceKrw", "60000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    @Test
    void 가격_오름차순으로_정렬한다() throws Exception {
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "가격A", 5000);
        saveProductWithOptions(categoryId, "가격B", 50000);

        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "PRICE_ASC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("가격A"))
                .andExpect(jsonPath("$.data.items[1].name").value("가격B"));
    }

    @Test
    void 최신순_커서_이후의_상품만_응답한다() throws Exception {
        Long categoryId = fruitCategoryId();
        Long first = saveProductWithOptions(categoryId, "커서첫상품", 1000);
        Long second = saveProductWithOptions(categoryId, "커서둘째상품", 2000);

        String token = firstPageToken(categoryId, "CREATED_DESC");

        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "CREATED_DESC")
                        .param("pageToken", token)
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].productId")
                        .value(org.hamcrest.Matchers.not(
                                org.hamcrest.Matchers.hasItem(second.intValue()))));
    }

    /*
     * API-3-04 / UT-1-01 회귀 테스트.
     * 가격 정렬 상태에서 커서로 다음 페이지를 넘겼을 때, 정렬 축(가격)과 커서 축이
     * 일치해야 행이 건너뛰거나 중복되지 않는다. 이전에는 커서가 id 만 봐서
     * 가격 정렬 2페이지부터 결과가 깨졌었다.
     */
    @Test
    void 가격_정렬_상태에서_커서로_다음_페이지를_넘겨도_행이_빠지거나_겹치지_않는다() throws Exception {
        // given — 가격 오름차순 정렬 시 1000, 2000, 3000 순으로 나와야 한다
        Long categoryId = fruitCategoryId();
        Long first = saveProductWithOptions(categoryId, "가격1000", 1000);
        Long second = saveProductWithOptions(categoryId, "가격2000", 2000);
        Long third = saveProductWithOptions(categoryId, "가격3000", 3000);

        // when — 1페이지(가격 오름차순, 크기 1)를 요청해 커서를 얻는다
        String firstPageJson = mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "PRICE_ASC")
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].productId").value(first.intValue()))
                .andReturn().getResponse().getContentAsString();

        String nextToken = com.jayway.jsonpath.JsonPath.read(firstPageJson, "$.data.nextPageToken");

        // then — 그 토큰으로 2페이지를 요청하면 1페이지에서 이미 받은 first 는 다시 안 나오고,
        // 정확히 second 부터 이어져야 한다 (id 기준 커서였다면 정렬 축 불일치로 여기서 깨졌다)
        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", "PRICE_ASC")
                        .param("pageSize", "1")
                        .param("pageToken", nextToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].productId").value(second.intValue()));
    }

    @Test
    void 페이지_크기만큼만_응답하고_다음_페이지_토큰을_준다() throws Exception {
        Long categoryId = fruitCategoryId();
        for (int i = 0; i < 3; i++) {
            saveProductWithOptions(categoryId, "페이지상품" + i, 1000 + i);
        }

        mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.nextPageToken").exists());
    }

    @Test
    void 잘못된_정렬값이_오면_400을_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products").param("sort", "INVALID_SORT"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 가격_범위가_뒤집히면_400을_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products")
                        .param("minPriceKrw", "50000")
                        .param("maxPriceKrw", "10000"))
                .andExpect(status().isBadRequest());
    }

    // 1페이지 요청 후 응답의 nextPageToken 을 그대로 꺼낸다. 커서 테스트용 헬퍼
    private String firstPageToken(Long categoryId, String sort) throws Exception {
        String json = mockMvc.perform(get("/v1/products")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("sort", sort)
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.data.nextPageToken");
    }
    
    @Test
    void 상품_상세를_조회한다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        Long productId = saveProductWithOptions(categoryId, "감귤", 12900, 32000);

        // when, then
        mockMvc.perform(get("/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(productId.intValue()))
                .andExpect(jsonPath("$.data.name").value("감귤"))
                .andExpect(jsonPath("$.data.category.name").value("과일"))
                .andExpect(jsonPath("$.data.options", hasSize(2)))
                .andExpect(jsonPath("$.data.review.count").value(0));
    }

    @Test
    void 확정된_이미지만_상세_응답에_나온다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        Long productId = saveProductWithOptions(categoryId, "감귤", 12900);
        ProductImage confirmed = ProductImage.register(productId, "products/ab/confirmed.jpg");
        confirmed.confirm();
        productImageRepository.save(confirmed);
        productImageRepository.save(ProductImage.register(productId, "products/ab/pending.jpg"));

        // when, then — PENDING 이미지는 안 나오고 CONFIRMED 하나만 나온다
        mockMvc.perform(get("/v1/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images", hasSize(1)));
    }

    @Test
    void 존재하지_않는_상품을_조회하면_404를_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT-001"));
    }

    @Test
    void 삭제된_상품을_조회하면_404를_응답한다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        Long productId = saveProductWithOptions(categoryId, "복숭아", 9900);
        softDelete(productId);

        // when, then
        mockMvc.perform(get("/v1/products/" + productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PRODUCT-001"));
    }

    @Test
    void 비로그인_상태에서도_상세_조회가_가능하다() throws Exception {
        Long categoryId = fruitCategoryId();
        Long productId = saveProductWithOptions(categoryId, "감귤", 12900);

        mockMvc.perform(get("/v1/products/" + productId))
                .andExpect(status().isOk());
    }
    
    @Test
    void 상품명_부분_일치로_검색한다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "제주 감귤", 12900);
        saveProductWithOptions(categoryId, "복숭아", 9900);

        // when, then — "감귤"로 검색하면 "제주 감귤"만 나온다
        mockMvc.perform(get("/v1/products:search").param("query", "감귤"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("제주 감귤"));
    }

    @Test
    void 검색은_목록과_같은_카테고리_필터를_받는다() throws Exception {
        // given
        Long fruitId = fruitCategoryId();
        saveProductWithOptions(fruitId, "감귤", 12900);

        // when, then
        mockMvc.perform(get("/v1/products:search")
                        .param("query", "감귤")
                        .param("categoryId", String.valueOf(fruitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)));
    }

    @Test
    void 검색어_없이_요청하면_400을_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products:search"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검색어가_공백이면_400을_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products:search").param("query", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 검색어가_100자를_넘으면_400을_응답한다() throws Exception {
        String tooLong = "감".repeat(101);
        mockMvc.perform(get("/v1/products:search").param("query", tooLong))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 비로그인_상태에서도_검색이_가능하다() throws Exception {
        mockMvc.perform(get("/v1/products:search").param("query", "감귤"))
                .andExpect(status().isOk());
    }

    @Test
    void 검색_결과가_없으면_빈_배열을_응답한다() throws Exception {
        mockMvc.perform(get("/v1/products:search").param("query", "존재하지않는상품명"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }
    
    @Test
    void 검색어에_와일드카드_문자가_있어도_리터럴로_취급한다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "20% 할인 세트", 12900);
        saveProductWithOptions(categoryId, "일반 상품", 9900);

        // when, then — "%"가 LIKE 와일드카드로 해석되면 전체 상품이 다 걸린다.
        // 이스케이프가 제대로 되면 "20% 할인 세트" 하나만 나와야 한다
        mockMvc.perform(get("/v1/products:search")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("query", "20%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name").value("20% 할인 세트"));
    }
    
    @Test
    void 검색_결과에서_커서로_다음_페이지를_넘겨도_검색어_조건이_유지된다() throws Exception {
        // given
        Long categoryId = fruitCategoryId();
        saveProductWithOptions(categoryId, "감귤A", 1000);
        saveProductWithOptions(categoryId, "감귤B", 2000);
        saveProductWithOptions(categoryId, "복숭아", 3000);   // 검색어에 안 걸려야 한다

        // when — 1페이지
        String firstPageJson = mockMvc.perform(get("/v1/products:search")
                        .param("query", "감귤")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andReturn().getResponse().getContentAsString();

        String nextToken = com.jayway.jsonpath.JsonPath.read(firstPageJson, "$.data.nextPageToken");

        // then — 2페이지도 query 를 다시 보내면, "복숭아"가 안 새어나오고 감귤만 남아야 한다
        mockMvc.perform(get("/v1/products:search")
                        .param("query", "감귤")
                        .param("categoryId", String.valueOf(categoryId))
                        .param("pageSize", "1")
                        .param("pageToken", nextToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].name")
                        .value(org.hamcrest.Matchers.containsString("감귤")));
    }
}