package com.freshmarket.common.auth;

import java.net.SocketTimeoutException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;

/**
 * Redis(또는 DB) 장애 로그에서 타임아웃과 그 외 DataAccessException을 구별하기 위한 라벨.
 *
 * event= 이름은 건드리지 않는다 — 기존 알람/대시보드가 이미 그 이름에 의존하고 있어서(이름이
 * 바뀌면 알람이 조용히 죽는다, SchedulerFreshnessMetricTest 참고) 원인만 별도 필드(cause=)로
 * 덧붙이는 용도로만 쓴다.
 *
 * Lettuce/Jedis 등 실제 드라이버 예외 타입에 직접 의존하지 않으려고 원인 체인을 훑으며
 * QueryTimeoutException/SocketTimeoutException이거나 클래스 이름에 "Timeout"이 들어간
 * 경우까지 타임아웃으로 본다. MemberTokenService(리프레시 토큰 발급/재발급/폐기),
 * JwtAuthenticationFilter(액세스 토큰 커트라인 조회), AuthRateLimitFilter(로그인 rate limit)가
 * 공유해서 쓴다.
 */
public final class RedisFailureClassifier {

    private static final String TIMEOUT = "TIMEOUT";
    private static final String OTHER = "OTHER";

    private RedisFailureClassifier() {
    }

    public static String causeLabel(DataAccessException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof QueryTimeoutException || t instanceof SocketTimeoutException) {
                return TIMEOUT;
            }
            if (t.getClass().getSimpleName().contains("Timeout")) {
                return TIMEOUT;
            }
        }
        return OTHER;
    }
}
