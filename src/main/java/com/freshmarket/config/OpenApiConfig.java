package com.freshmarket.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/*
 * springdoc이 Swagger UI에 Authorize(자물쇠) 버튼을 그리려면 SecurityScheme 정의가 필요하다.
 * 이게 없으면 JwtAuthenticationFilter가 Authorization: Bearer 헤더를 지원해도 Swagger UI에서
 * 넣을 방법이 없다. 인증이 필요한 API 전부에 기본으로 이 스킴을 요구하도록
 * OpenAPIDefinition.security에 걸어둔다 — 개별 컨트롤러/메서드에 매번 @SecurityRequirement를
 * 붙일 필요가 없다(permitAll 경로에 토큰을 안 넣어도 어차피 서버가 무시하므로 무해하다).
 */
@Configuration
@OpenAPIDefinition(security = @SecurityRequirement(name = "bearerAuth"))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "POST /v1/auth/tokens 로그인 응답의 accessToken(또는 응답 쿠키)을 넣는다. "
                + "관리자는 POST /v1/admin/auth/tokens 로그인 응답을 사용한다."
)
public class OpenApiConfig {
}
