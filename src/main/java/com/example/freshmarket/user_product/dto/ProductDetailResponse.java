package com.example.freshmarket.user_product.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

@Getter
@Builder
public class ProductDetailResponse {
    private Long id;
    private String productCode;
    private String name;
    private String categoryName;
    private String supplierName;
    private Integer price;
    private String saleStatus;
    private String storageType;
    private Integer minShelfLifeDays;
    private String description;
    private List<String> imageUrls;
    private Instant createdAt;
}