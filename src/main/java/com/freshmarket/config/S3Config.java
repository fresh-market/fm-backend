package com.freshmarket.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * 상품 이미지 업로드(#21)가 쓰는 S3 클라이언트. presigned PUT 발급은 S3Presigner가,
 * HeadObject/DeleteObject 같은 직접 호출은 S3Client가 맡는다(백엔드공통_이미지저장소_설계.md 6.2절).
 * 둘 다 같은 리전 하나만 보므로 리전을 한 곳에서만 읽는다.
 */
@Configuration
public class S3Config {

    /*
     * AWS SDK v2는 apiCallTimeout/apiCallAttemptTimeout을 기본적으로 꺼 둔다(무제한) — S3가 응답을
     * 안 주면 호출이 사실상 무기한 걸릴 수 있다. AdminProductImageService.confirm()/delete()는 이
     * 호출을 DB 트랜잭션 밖에서 하지만(DI-4-02), 그와 별개로 호출 자체가 무기한 걸리는 상황은
     * 막아야 한다 — 안 그러면 그 요청을 처리하던 스레드/커넥션이 응답 없는 S3를 무기한 기다린다.
     * WebClientConfig(카카오 호출)가 명시적 타임아웃을 두는 것과 같은 이유다.
     */
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

    /*
     * (REL-2-01) apiCallAttemptTimeout은 호출 전체(연결+전송+수신)의 상한이라, TCP 연결 자체가
     * 못 열리는 상황을 별도로 빨리 잡아내지 못한다. common/qa-reliability-guideline.md 2장의
     * "외부 연동" 기준(연결 1s/읽기 3s)대로 HTTP 클라이언트에 연결 타임아웃을 따로 둔다.
     */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(1);

    @Bean
    public S3Presigner s3Presigner(@Value("${s3.region}") String region) {
        return S3Presigner.builder()
                .region(Region.of(region))
                .build();
    }

    @Bean
    public S3Client s3Client(@Value("${s3.region}") String region) {
        return S3Client.builder()
                .region(Region.of(region))
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(CONNECTION_TIMEOUT))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }
}
