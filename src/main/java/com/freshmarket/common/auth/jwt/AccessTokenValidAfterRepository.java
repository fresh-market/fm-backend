package com.freshmarket.common.auth.jwt;

import java.time.Duration;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * "이 시각 이전에 발급된 accessToken은 전부 무효"라는 계정 단위 커트라인을 Redis에 저장하는 저장소.
 * key 포맷은 RefreshTokenRepository와 맞춰 "accessTokenValidAfter:{role}:{id}".
 */
@Repository
@RequiredArgsConstructor
public class AccessTokenValidAfterRepository {

    private static final String KEY_PREFIX = "accessTokenValidAfter:";

    private final StringRedisTemplate redisTemplate;

    public void invalidateBefore(String role, Long id, LocalDateTime cutoff, Duration ttl) {
        redisTemplate.opsForValue().set(key(role, id), cutoff.toString(), ttl);
    }

    public boolean isValidAfter(String role, Long id, LocalDateTime tokenIssuedAt) {
        String stored = redisTemplate.opsForValue().get(key(role, id));
        if (stored == null) {
            return true;
        }
        LocalDateTime cutoff = LocalDateTime.parse(stored);
        return !tokenIssuedAt.isBefore(cutoff);
    }

    private String key(String role, Long id) {
        return KEY_PREFIX + role + ":" + id;
    }
}
