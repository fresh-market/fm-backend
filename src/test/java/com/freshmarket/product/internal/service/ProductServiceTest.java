package com.freshmarket.product.internal.service;

import static com.freshmarket.product.internal.entity.ProductFixture.삭제된_상품;
import static com.freshmarket.product.internal.entity.ProductFixture.상품;
import static com.freshmarket.product.internal.entity.ProductFixture.옵션;
import static com.freshmarket.product.internal.entity.ProductFixture.카테고리;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.internal.dto.ProductDetailResponse;
import com.freshmarket.product.internal.dto.ProductListItem;
import com.freshmarket.product.internal.dto.ProductSearchCondition;
import com.freshmarket.product.internal.dto.ProductSortType;
import com.freshmarket.product.internal.dto.ProductWithMinPrice;
import com.freshmarket.product.internal.entity.Category;
import com.freshmarket.product.internal.entity.Product;
import com.freshmarket.product.internal.entity.ProductImage;
import com.freshmarket.product.internal.entity.ProductOption;
import com.freshmarket.product.internal.entity.SaleStatus;
import com.freshmarket.product.internal.entity.UploadStatus;
import com.freshmarket.product.internal.exception.ProductErrorCode;
import com.freshmarket.product.internal.exception.ProductException;
import com.freshmarket.product.internal.repository.CategoryRepository;
import com.freshmarket.product.internal.repository.ProductImageRepository;
import com.freshmarket.product.internal.repository.ProductOptionRepository;
import com.freshmarket.product.internal.repository.ProductQueryRepository;
import com.freshmarket.product.internal.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

// ProductService의 목록 조회, 검색, 상세 조회, 페이징, 응답 변환 분기를 검증한다
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 19, 10, 0);

    @Mock
    private ProductQueryRepository productQueryRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private ProductOptionRepository productOptionRepository;

    @Mock
    private ProductImageRepository productImageRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private ProductService productService;

    @Test
    void 조건에_맞는_상품_목록을_조회한다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                4L, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("감귤");
        assertThat(result.items().get(0).minPriceKrw()).isEqualTo(12900);
    }

    @Test
    void 결과가_pageSize보다_많으면_다음_페이지_토큰을_준다() {
        // given — pageSize 2 인데 리포지토리가 3건(= pageSize + 1)을 준 상황
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.CREATED_DESC, null, 2);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(3L, "상품3", 1L, "카테고리", 1000, SaleStatus.ON_SALE, false, NOW),
                new ProductWithMinPrice(2L, "상품2", 1L, "카테고리", 2000, SaleStatus.ON_SALE, false, NOW),
                new ProductWithMinPrice(1L, "상품1", 1L, "카테고리", 3000, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then — 초과분(1건)은 잘리고, 페이지의 마지막 행(id=2) 기준으로 토큰이 만들어진다
        assertThat(result.items()).hasSize(2);
        assertThat(result.nextPageToken()).isNotNull();
        PageCursor decoded = PageTokens.decode(result.nextPageToken());
        assertThat(decoded.id()).isEqualTo(2L);
        assertThat(decoded.sortValue()).isEqualTo(NOW.toString());
    }

    @Test
    void 결과가_pageSize_이하면_다음_페이지_토큰이_없다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 조회_결과가_없으면_빈_목록과_토큰_없음을_준다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                999L, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of());

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items()).isEmpty();
        assertThat(result.nextPageToken()).isNull();
    }

    @Test
    void 소속_옵션이_전부_품절이면_soldOut이_true로_내려간다() {
        // given — ProductQueryRepository의 집계(MIN(sold_out)) 결과를 그대로 흉내낸다
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, true, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items().get(0).soldOut()).isTrue();
    }

    @Test
    void 살아있는_옵션이_하나라도_있으면_soldOut이_false로_내려간다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items().get(0).soldOut()).isFalse();
    }

    @Test
    void 카테고리_정보가_요약되어_내려간다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items().get(0).category().categoryId()).isEqualTo(4L);
        assertThat(result.items().get(0).category().name()).isEqualTo("과일");
    }

    @Test
    void 가격_정렬이면_최저가_기준으로_다음_페이지_토큰을_만든다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, null, ProductSortType.PRICE_ASC, null, 1);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW),
                new ProductWithMinPrice(2L, "복숭아", 4L, "과일", 20000, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then — 커서 정렬값이 createdAt 이 아니라 minPrice(12900) 여야 한다
        PageCursor decoded = PageTokens.decode(result.nextPageToken());
        assertThat(decoded.sortValue()).isEqualTo("12900");
    }

    @Test
    void 검색어가_있으면_리포지토리에_그대로_전달된다() {
        // given — 서비스는 검색과 목록을 같은 메서드로 처리한다. query 유무만 다르다
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, "감귤", ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of(
                new ProductWithMinPrice(1L, "감귤", 4L, "과일", 12900, SaleStatus.ON_SALE, false, NOW)));

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("감귤");
    }

    @Test
    void 검색_결과가_없으면_빈_목록을_준다() {
        // given
        ProductSearchCondition condition = new ProductSearchCondition(
                null, null, null, "존재하지않는상품명", ProductSortType.CREATED_DESC, null, 20);
        when(productQueryRepository.search(condition)).thenReturn(List.of());

        // when
        CursorPageResponse<ProductListItem> result = productService.getProducts(condition);

        // then
        assertThat(result.items()).isEmpty();
    }

    @Test
    void 상품_상세를_조회한다() {
        // given
        Product product = 상품(1L, "감귤", 4L);
        Category category = 카테고리(4L, "과일");
        ProductOption option = 옵션(1L, "1kg", 12900);
        ProductImage image = ProductImage.register(1L, "req-image-1", "products/ab/1.jpg");
        image.confirm();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(category));
        when(productOptionRepository.findByProductIdAndSaleStatusNot(1L, SaleStatus.OFF_SALE))
                .thenReturn(List.of(option));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.CONFIRMED))
                .thenReturn(List.of(image));

        // when
        ProductDetailResponse result = productService.getProductDetail(1L);

        // then
        assertThat(result.name()).isEqualTo("감귤");
        assertThat(result.category().name()).isEqualTo("과일");
        assertThat(result.options()).hasSize(1);
        assertThat(result.options().get(0).name()).isEqualTo("1kg");
        assertThat(result.images()).hasSize(1);
        assertThat(result.review().count()).isEqualTo(0);
    }

    @Test
    void 상세_응답의_옵션_품절_여부는_soldOut_필드를_그대로_따른다() {
        // given — saleStatus는 ON_SALE(판매중)인데 재고 기반 soldOut은 true인 옵션.
        // saleStatus로 판정했다면 false가 나왔을 상황이라, 진짜 soldOut 필드를 보는지 구분된다
        Product product = 상품(1L, "감귤", 4L);
        Category category = 카테고리(4L, "과일");
        ProductOption option = 옵션(1L, "1kg", 12900);
        ReflectionTestUtils.setField(option, "soldOut", true);

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(category));
        when(productOptionRepository.findByProductIdAndSaleStatusNot(1L, SaleStatus.OFF_SALE))
                .thenReturn(List.of(option));
        when(productImageRepository.findByProductIdAndUploadStatus(1L, UploadStatus.CONFIRMED))
                .thenReturn(List.of());

        // when
        ProductDetailResponse result = productService.getProductDetail(1L);

        // then
        assertThat(result.options().get(0).saleStatus()).isEqualTo(SaleStatus.ON_SALE);
        assertThat(result.options().get(0).soldOut()).isTrue();
    }

    @Test
    void 존재하지_않는_상품을_조회하면_실패한다() {
        // given
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> productService.getProductDetail(999L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void 삭제된_상품을_조회하면_실패한다() {
        // given
        Product deleted = 삭제된_상품(2L, "복숭아", 4L);
        when(productRepository.findById(2L)).thenReturn(Optional.of(deleted));

        // when, then
        assertThatThrownBy(() -> productService.getProductDetail(2L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    void 옵션이나_이미지가_없어도_빈_목록으로_조회된다() {
        // given
        Product product = 상품(3L, "사과", 4L);
        Category category = 카테고리(4L, "과일");

        when(productRepository.findById(3L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(4L)).thenReturn(Optional.of(category));
        when(productOptionRepository.findByProductIdAndSaleStatusNot(3L, SaleStatus.OFF_SALE))
                .thenReturn(List.of());
        when(productImageRepository.findByProductIdAndUploadStatus(3L, UploadStatus.CONFIRMED))
                .thenReturn(List.of());

        // when
        ProductDetailResponse result = productService.getProductDetail(3L);

        // then
        assertThat(result.options()).isEmpty();
        assertThat(result.images()).isEmpty();
    }

    @Test
    void 카테고리가_없으면_상세_조회에_실패한다() {
        // given
        Product product = 상품(4L, "배", 4L);

        when(productRepository.findById(4L)).thenReturn(Optional.of(product));
        when(categoryRepository.findById(4L)).thenReturn(Optional.empty());

        // when, then
        assertThatThrownBy(() -> productService.getProductDetail(4L))
                .isInstanceOf(ProductException.class)
                .hasFieldOrPropertyWithValue("errorCode", ProductErrorCode.CATEGORY_NOT_FOUND);
    }
}