package com.freshmarket.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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
     * 안 주면 호출이 사실상 무기한 걸릴 수 있다. AdminProductImageService.confirm()/delete()가 이
     * 호출을 DB 쓰기 락을 쥔 채로 하므로(DI-4-03), 여기서 막지 않으면 락이 그만큼 오래 묶인다.
     * WebClientConfig(카카오 호출)가 명시적 타임아웃을 두는 것과 같은 이유다.
     */
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(5);

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
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .build())
                .build();
    }
}
