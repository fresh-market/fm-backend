package com.freshmarket.product.domain.client;

// HeadObject 응답에서 확정 판정에 필요한 값만 뽑은 것. 크기와 Content-Type은 저장하지 않고 그 자리에서만 쓴다
public record S3ObjectMetadata(long contentLength, String contentType) {
}
