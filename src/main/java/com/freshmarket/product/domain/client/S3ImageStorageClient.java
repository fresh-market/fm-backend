package com.freshmarket.product.domain.client;

import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/*
 * 상품 이미지 버킷 하나만 다루는 S3 접근 창구(백엔드공통_이미지저장소_설계.md 6.2절).
 * 업로드 URL은 S3Presigner로, 확정 확인·정리는 S3Client로 나눈다 — presigned PUT은
 * 오리진(S3)에 직접 서명하는 것이라 CloudFront 도메인을 쓸 수 없기 때문이다(INF-11-01 예외).
 */
@Slf4j
@Component
public class S3ImageStorageClient {

    private final S3Presigner s3Presigner;
    private final S3Client s3Client;
    private final String bucket;
    private final Duration presignedUrlExpiry;

    public S3ImageStorageClient(S3Presigner s3Presigner, S3Client s3Client,
            @Value("${s3.media-bucket}") String bucket,
            @Value("${s3.presigned-url-expiry-seconds}") long presignedUrlExpirySeconds) {
        this.s3Presigner = s3Presigner;
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.presignedUrlExpiry = Duration.ofSeconds(presignedUrlExpirySeconds);
    }

    // 이 objectKey에 contentType으로 PUT할 수 있는 presigned URL을 발급한다
    public String createPresignedPutUrl(String objectKey, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(presignedUrlExpiry)
                .putObjectRequest(putObjectRequest)
                .build();
        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return presigned.url().toString();
    }

    /*
     * 실제 업로드 여부를 확인한다(INF-11-10). 없으면(HeadObject 404) 빈 값을 준다 — 아직 PUT을
     * 안 했거나 실패한 것으로, 예외가 아니라 정상적으로 있을 수 있는 상태다.
     *
     * headObject()는 문서상 NoSuchKeyException을 던진다고 되어 있지만 실제로는 상태코드 404인
     * 일반 S3Exception을 던진다(SDK 쪽 알려진 불일치) — statusCode로 직접 구분한다.
     */
    public Optional<S3ObjectMetadata> headObject(String objectKey) {
        try {
            HeadObjectResponse response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
            return Optional.of(new S3ObjectMetadata(response.contentLength(), response.contentType()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    // 확정 조건(크기·형식)을 안 지킨 업로드를 지운다. 실패해도 재시도하지 않는다 — 정리 배치가 나중에 마저 치운다
    public void deleteObject(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (S3Exception e) {
            log.warn("event=IMAGE_DELETE_OBJECT_FAILED objectKey={}", objectKey, e);
        }
    }
}
