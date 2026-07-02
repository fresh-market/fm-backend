package com.example.freshmarket.user_product.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductListResponse {
    private Long id;
    private String productCode;
    private String name;
    private Integer price;
    private String saleStatus;
    private String mainImageUrl;
}