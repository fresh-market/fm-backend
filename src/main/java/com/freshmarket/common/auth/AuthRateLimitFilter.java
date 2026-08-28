package com.freshmarket.common.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
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
    private static final Pattern SAFE_IP = Pattern.compile("^[0-9a-fA-F.:]{1,45}$");
    private static final RedisScript<Long> RATE_LIMIT_SCRIPT = loadRateLimitScript();

    private final StringRedisTemplate redisTemplate;

    private static RedisScript<Long> loadRateLimitScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/auth_rate_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!"POST".equalsIgnoreCase(request.getMethod()) || !LIMITED_PATHS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isOverLimit(resolveClientIp(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isOverLimit(String ip) {
        String key = KEY_PREFIX + ip;
        try {
            Long count = redisTemplate.execute(
                    RATE_LIMIT_SCRIPT,
                    List.of(key),
                    String.valueOf(WINDOW.toMillis())
            );
            return count != null && count > LIMIT;
        } catch (DataAccessException e) {
            log.warn("event=RATE_LIMIT_CHECK_FAILED ip={} cause={} — fail-open으로 통과시킴",
                    ip, RedisFailureClassifier.causeLabel(e), e);
            return false;
        }
    }

    /**
     * ALB 기본 설정(append)은 실제 클라이언트 IP를 X-Forwarded-For의 마지막 항목에 덧붙인다.
     * 이 앱의 8080 포트는 ALB 보안 그룹에서만 접근 가능해야 한다. 그렇지 않으면 직접 접속한
     * 클라이언트가 X-Forwarded-For를 위조할 수 있다.
     */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }

        String[] addresses = forwarded.split(",");
        String candidate = addresses[addresses.length - 1].trim();
        return SAFE_IP.matcher(candidate).matches() ? candidate : request.getRemoteAddr();
    }
}
