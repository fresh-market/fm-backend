package com.freshmarket.product.internal.entity;

// 상품 이미지의 업로드 확정 상태. 발급만 된 PENDING에서 HeadObject 확인을 거쳐 CONFIRMED로만 전이한다
public enum UploadStatus {
    PENDING, CONFIRMED
}
