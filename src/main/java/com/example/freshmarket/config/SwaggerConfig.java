package com.example.freshmarket.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI() {
        Info info = new Info().title("Fresh Market API 문서").version("v1.0.0")
                .description("Fresh Market 쇼핑몰 프로젝트의 백엔드 API 명세서입니다.");

        return new OpenAPI().info(info);
    }
}
