package com.freshmarket.product.domain.client;

import java.util.Optional;

/*
 * 이미지 저장소 접근 포트다(DPB-3-01). product 도메인이 이 인터페이스를 소유하고, 구현체(S3ImageStorageClient)가
 * 외부 스펙에 맞춘다 — payment 도메인의 PaymentGateway/MockPaymentGateway와 같은 구조다.
 */
public interface ImageStorageClient {

    String createPresignedPutUrl(String objectKey, String contentType, long contentLength);

    Optional<S3ObjectMetadata> headObject(String objectKey);

    void deleteObject(String objectKey);

    void deleteObjectOrThrow(String objectKey);
}
