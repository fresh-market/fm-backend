package com.freshmarket.product.domain.service;

import com.freshmarket.common.response.CursorPageResponse;
import com.freshmarket.product.domain.dto.CategorySummary;
import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.domain.dto.ProductDetailResponse;
import com.freshmarket.product.domain.dto.ProductDetailImageResponse;
import com.freshmarket.product.domain.dto.ProductListItem;
import com.freshmarket.product.domain.dto.ProductOptionResponse;
import com.freshmarket.product.domain.dto.ProductSearchCondition;
import com.freshmarket.product.domain.dto.ProductSortType;
import com.freshmarket.product.domain.dto.ProductWithMinPrice;
import com.freshmarket.product.domain.dto.ReviewSummaryResponse;
import com.freshmarket.product.domain.entity.Category;
import com.freshmarket.product.domain.entity.Product;
import com.freshmarket.product.domain.entity.ProductImage;
import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import com.freshmarket.product.domain.entity.UploadStatus;
import com.freshmarket.product.domain.exception.ProductErrorCode;
import com.freshmarket.product.domain.exception.ProductException;
import com.freshmarket.product.domain.repository.CategoryRepository;
import com.freshmarket.product.domain.repository.ProductImageRepository;
import com.freshmarket.product.domain.repository.ProductOptionRepository;
import com.freshmarket.product.domain.repository.ProductQueryRepository;
import com.freshmarket.product.domain.repository.ProductRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회원에게 보이는 상품 조회를 맡는다. 관리자 조회는 AdminProductService 가 따로 맡는다
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductQueryRepository productQueryRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final ProductImageRepository productImageRepository;
    private final CategoryRepository categoryRepository;

    /*
     * 상품 목록을 조건에 맞춰 조회한다.
     * 리포지토리가 pageSize + 1 건을 주므로 초과분을 잘라내고 다음 페이지 여부를 판단한다.
     */
    public CursorPageResponse<ProductListItem> getProducts(ProductSearchCondition condition) {
        List<ProductWithMinPrice> found = productQueryRepository.search(condition);

        boolean hasNext = found.size() > condition.pageSize();
        List<ProductWithMinPrice> page = hasNext
                ? found.subList(0, condition.pageSize())
                : found;

        List<ProductListItem> items = page.stream()
                .map(ProductService::toItem)
                .toList();

        return CursorPageResponse.of(items, nextTokenOf(page, hasNext, condition.sort()));
    }

    /*
     * 상품 상세를 조회한다. 삭제된 상품은 없는 것과 같이 취급한다 (명세: "없거나 삭제된 상품").
     * 옵션은 목록 조회와 같은 규칙으로 OFF_SALE 을 뺀다. 품절은 표시만 하고 노출은 유지한다.
     * images 는 CONFIRMED 상태만, review 는 도메인 신설 전이라 스텁으로 내려간다.
     */
    public ProductDetailResponse getProductDetail(Long productId) {
        Product product = productRepository.findById(productId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // FK 무결성상 항상 존재해야 한다. 방어적으로만 처리한다
        Category category = categoryRepository.findById(product.getCategoryId())
                .orElseThrow(() -> new ProductException(ProductErrorCode.CATEGORY_NOT_FOUND));

        List<ProductOptionResponse> options = productOptionRepository
                .findByProductIdAndSaleStatusNot(productId, SaleStatus.OFF_SALE).stream()
                .map(ProductService::toOptionResponse)
                .toList();

        List<ProductDetailImageResponse> images = productImageRepository
                .findByProductIdAndUploadStatus(productId, UploadStatus.CONFIRMED).stream()
                // 대표 먼저, 그다음 sort_order(작을수록 앞), 같으면 id 로 순서를 고정한다
                .sorted(Comparator.comparing(ProductImage::isMain).reversed()
                        .thenComparing(ProductImage::getSortOrder)
                        .thenComparing(ProductImage::getId))
                .map(ProductService::toImageResponse)
                .toList();

        return new ProductDetailResponse(
                product.getId(),
                product.getProductCode(),
                product.getName(),
                product.getDescription(),
                new CategorySummary(category.getId(), category.getName()),
                product.getStorageType(),
                product.getSaleStatus(),
                options,
                images,
                new ReviewSummaryResponse(0, null));
    }

    /*
     * 목록 조회 결과를 응답 표현으로 옮긴다.
     *
     * soldOut 은 ProductQueryRepository의 집계(조인된 옵션이 전부 품절인지)를 그대로 옮긴다.
     * mainImageUrl 은 product_image 조인과 CDN 설정이 필요해 아직 채우지 않는다.
     */
    private static ProductListItem toItem(ProductWithMinPrice row) {
        return new ProductListItem(
                row.productId(),
                row.name(),
                new CategorySummary(row.categoryId(), row.categoryName()),
                row.minPrice(),
                row.saleStatus(),
                row.soldOut(),
                null);
    }

    private static ProductOptionResponse toOptionResponse(ProductOption option) {
        return new ProductOptionResponse(
                option.getId(),
                option.getName(),
                option.getPrice(),
                option.getSaleStatus(),
                option.isSoldOut());
    }

    // url 은 CDN 설정이 없어 지금은 null 이다 (ProductDetailResponse 참고)
    private static ProductDetailImageResponse toImageResponse(ProductImage image) {
        return new ProductDetailImageResponse(
                image.getId(), null, image.isMain(), image.getSortOrder());
    }

    /*
     * 다음 페이지 토큰. 마지막 행의 정렬 기준값과 id 로 커서를 만든다.
     * 정렬 축(가격/생성일)에 맞는 값을 넣어야 다음 페이지에서 리포지토리가
     * 같은 축으로 비교할 수 있다 (API-3-04).
     */
    private static String nextTokenOf(
            List<ProductWithMinPrice> page, boolean hasNext, ProductSortType sort) {
        if (!hasNext || page.isEmpty()) {
            return null;
        }
        ProductWithMinPrice last = page.get(page.size() - 1);
        String sortValue = switch (sort) {
            case PRICE_ASC, PRICE_DESC -> String.valueOf(last.minPrice());
            case CREATED_DESC, SALES_DESC -> last.createdAt().toString();
        };
        return PageTokens.encode(new PageCursor(last.productId(), sortValue));
    }
}