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
import software.amazon.awssdk.core.exception.SdkException;
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
public class S3ImageStorageClient implements ImageStorageClient {

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

    /*
     * 이 objectKey에 contentType·contentLength로 PUT할 수 있는 presigned URL을 발급한다.
     * contentLength를 서명에 포함해야(INF-11-06) 신고보다 큰 파일을 PUT하면 서명 불일치로 S3가
     * 자체적으로 거부한다 — 안 넣으면 상한을 넘는 파일이 일단 S3에 다 올라간 뒤에야
     * confirm()에서 걸러지므로, 이미 나간 업로드 대역폭과 PUT 비용을 되돌릴 수 없다
     * (백엔드공통_이미지저장소_설계.md 6.2절 "상한을 강제하는 자리").
     */
    @Override
    public String createPresignedPutUrl(String objectKey, String contentType, long contentLength) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
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
    @Override
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

    /*
     * 확정 조건(크기·형식)을 안 지킨 업로드를 지운다. 실패해도 예외를 던지지 않는다 — 이 호출부
     * (confirm()의 불일치 처리)는 행을 PENDING으로 남겨 두므로, 여기서 지우지 못해도 나중에
     * 정리 배치가 같은 키로 다시 시도할 수 있다. 관리자가 이미지를 직접 지우는 delete()는 행 자체가
     * 사라져 재시도 대상이 없어지므로 이 메서드가 아니라 deleteObjectOrThrow()를 쓴다(INF-11-08).
     *
     * (FUN-2-01) S3Exception만이 아니라 SdkException을 잡는다 — 연결 실패·타임아웃으로 던져지는
     * SdkClientException은 S3Exception의 형제 타입이라(둘 다 SdkException 하위) 좁게 잡으면 여기서
     * 못 잡고 새어나가, "실패해도 예외를 던지지 않는다"는 이 메서드의 계약이 깨진다.
     */
    @Override
    public void deleteObject(String objectKey) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .build());
        } catch (SdkException e) {
            log.warn("event=IMAGE_DELETE_OBJECT_FAILED objectKey={}", objectKey, e);
        }
    }

    /*
     * S3 삭제가 실패하면 예외를 던진다(INF-11-08). "객체 먼저 지우고 행을 지운다"는 순서가
     * 실제로 고아를 막으려면, 객체 삭제 실패가 호출부에 전달되어 행 삭제를 막아야 한다 —
     * 삼키면 행만 사라지고 객체는 참조를 잃어 영영 못 찾는 고아가 된다.
     */
    @Override
    public void deleteObjectOrThrow(String objectKey) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .build());
    }
}
