package com.example.freshmarket.user_product.service;

import com.example.freshmarket.user_product.domain.Product;
import com.example.freshmarket.user_product.domain.SaleStatus;
import com.example.freshmarket.user_product.dto.ProductDetailResponse;
import com.example.freshmarket.user_product.dto.ProductListResponse;
import com.example.freshmarket.user_product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    // 상품 목록 / 메인 조회 (카테고리 필터)
    public Page<ProductListResponse> getProductList(Long categoryId, Pageable pageable) {
        Page<Product> products = (categoryId != null) ?
                productRepository.findAllBySaleStatusAndCategory_IdAndDeletedAtIsNull(
                        SaleStatus.ON_SALE, categoryId, pageable)
                : productRepository.findAllBySaleStatusAndDeletedAtIsNull(
                SaleStatus.ON_SALE, pageable);

        return products.map(this::toListResponse);

    } // getProductList

    // 상품명 검색
    public Page<ProductListResponse> searchByName(String keyword, Pageable pageable) {
        Page<Product> products = productRepository
                .findAllByNameContainingAndSaleStatusAndDeletedAtIsNull(
                        keyword, SaleStatus.ON_SALE, pageable
                );
        return products.map(this::toListResponse);
    }

    // 상품 상세 조회
    public ProductDetailResponse getProductDetail(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다. id = " + id));

        return toDetailResponse(product);
    }

    // Entity -> ProductListResponse 변환
    private ProductListResponse toListResponse(Product product) {
        return ProductListResponse.builder()
                .id(product.getId())
                .productCode(product.getProductCode())
                .name(product.getName())
                .price(product.getPrice())
                .saleStatus(product.getSaleStatus().name())
                .build();
    } // toListResponse

    // Entity -> ProductDetailResponse 변환
    private ProductDetailResponse toDetailResponse(Product product) {
        return ProductDetailResponse.builder()
                .id(product.getId())
                .productCode(product.getProductCode())
                .name(product.getName())
                .categoryName(product.getCategory().getName())
                .supplierName(product.getSupplier().getName())
                .price(product.getPrice())
                .saleStatus(product.getSaleStatus().name())
                .storageType(product.getStorageType().name())
                .minShelfLifeDays(product.getMinShelfLifeDays())
                .description(product.getDescription())
                .createdAt(product.getCreatedAt())
                .build();
    } // toDetailResponse

}
