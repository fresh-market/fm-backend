package com.freshmarket.common.auth;

import com.freshmarket.common.auth.jwt.AccessTokenValidAfterRepository;
import com.freshmarket.common.auth.jwt.JwtAuthenticationFilter;
import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/*
 * 도메인 필터 체인이 공유하는 배선이다.
 *
 * 체인을 도메인마다 두면 csrf, cors, 세션 정책, 인증 필터, 오류 위임을 매번 다시 적게 된다.
 * 그러면 한 도메인만 빠뜨려도 그 경로의 보안 성질이 조용히 달라진다.
 * 그 다섯 가지를 여기 한 곳에 모아 두고 각 체인은 자기 경로와 권한만 적는다.
 */
@Component
public class ApiSecurityDefaults {

    /*
     * 도메인 체인이 쓰는 우선순위다.
     * 도메인끼리 경로가 겹치지 않으므로 서로 같은 값이어도 매칭 결과가 하나로 정해진다.
     * 겹치는 경로를 두 도메인이 주장하는 것은 그 자체로 경계 설계 문제다.
     */
    public static final int DOMAIN_CHAIN_ORDER = 100;

    private final HandlerExceptionResolver handlerExceptionResolver;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenValidAfterRepository accessTokenValidAfterRepository;
    private final StringRedisTemplate redisTemplate;
    private final CorsConfigurationSource corsConfigurationSource;

    public ApiSecurityDefaults(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            JwtTokenProvider jwtTokenProvider,
            AccessTokenValidAfterRepository accessTokenValidAfterRepository,
            StringRedisTemplate redisTemplate,
            CorsConfigurationSource corsConfigurationSource) {
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.jwtTokenProvider = jwtTokenProvider;
        this.accessTokenValidAfterRepository = accessTokenValidAfterRepository;
        this.redisTemplate = redisTemplate;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    /*
     * 인증이 필요한 API 체인의 공통 부분을 얹는다.
     * 호출한 쪽이 securityMatcher 와 authorizeHttpRequests 를 이어 붙이고 build() 한다.
     */
    public HttpSecurity apply(HttpSecurity http) throws Exception {
        return http
                /*
                 * 서버 세션을 두지 않으므로 CSRF 토큰을 보관할 곳이 없다.
                 * accessToken 이 쿠키로 오가므로 CSRF 노출 범위는 인증이 필요한 모든 API 다.
                 * SameSite=Strict 가 대부분을 막지만 완전한 방어는 아니다.
                 * CSRF 토큰 도입 여부는 docs/api/auth.md 에 열린 채로 남아 있다.
                 */
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)

                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, accessTokenValidAfterRepository),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(
                        new AuthRateLimitFilter(redisTemplate),
                        UsernamePasswordAuthenticationFilter.class)

                /*
                 * 필터에서 난 예외를 MVC 예외 처리로 되돌린다.
                 * 이렇게 하지 않으면 인증 실패 응답만 여기서 따로 만들게 되어 오류 구조가 갈린다.
                 * handler 자리에 null 을 주는 것은 이 시점에 처리할 컨트롤러 메서드가 없기 때문이다.
                 */
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, exception) ->
                                handlerExceptionResolver.resolveException(request, response, null, exception))
                        .accessDeniedHandler((request, response, exception) ->
                                handlerExceptionResolver.resolveException(request, response, null, exception)));
    }
}
