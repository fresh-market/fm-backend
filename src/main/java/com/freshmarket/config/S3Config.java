package com.freshmarket.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                .build();
    }
}
