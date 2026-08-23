package com.freshmarket.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/*
 * (2026-08-20, SEC-6-01/SEC-6-02) 로그인/재발급이 permitAll이라 시도 횟수를 막는 장치가 없었다.
 * 회원 로그인엔 비밀번호가 없어서(카카오 OAuth) 브루트포스 대상은 아니지만, 요청 한 건이 그대로
 * 카카오 토큰 엔드포인트 호출로 이어진다 — 막아두지 않으면 한 클라이언트가 우리 스레드와 카카오
 * 앱 쿼터를 같이 태워서 전체 로그인이 막히는 서비스 장애로 번질 수 있다.
 *
 * 새 라이브러리(bucket4j 등) 없이 이미 있는 Redis로 IP당 고정 윈도우 카운터만 둔다. 숫자(분당
 * 10회)는 잠정값이라 팀 확인 필요.
 *
 * Redis 장애 시엔 세지 못하는 것뿐이지 막을 이유가 없어서 fail-open으로 통과시킨다 —
 * JwtAuthenticationFilter.isValidAfterCutoff()와 같은 이유(REL-2-11): 이 필터 하나 때문에
 * Redis 블립마다 로그인 전체가 막히면 안 된다.
 */
@Slf4j
@RequiredArgsConstructor
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "authRateLimit:";
    private static final Set<String> LIMITED_PATHS = Set.of("/v1/auth/tokens", "/v1/auth/tokens:refresh");
    private static final int LIMIT = 10;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final StringRedisTemplate redisTemplate;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isOverLimit(request.getRemoteAddr())) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isOverLimit(String ip) {
        String key = KEY_PREFIX + ip;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1) {
                redisTemplate.expire(key, WINDOW);
            }
            return count != null && count > LIMIT;
        } catch (DataAccessException e) {
            log.warn("event=RATE_LIMIT_CHECK_FAILED ip={} — fail-open으로 통과시킴", ip, e);
            return false;
        }
    }
}
