package com.freshmarket.common.auth.opaque;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.freshmarket.common.auth.jwt.TokenType;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Refresh Token 저장소. 순수 Redis 저장소 — Member/Admin을 전혀 모른다. Redis 장애 시
 * DataAccessException을 그대로 던지며, DB 백업/폴백은 호출자(도메인 소유의 ~TokenService)의
 * 책임이다.
 *
 * (2026-08-19) opaque 토큰 전환(SEC-1-04 정리): 리프레시 토큰이 JWT일 때는 클라이언트가 보낸
 * 토큰 자체에서 role/id를 꺼낼 수 있어 "role:id → 토큰" 한 방향 키만으로 충분했다. opaque(무작위
 * 문자열)는 토큰만 봐서는 누구 건지 전혀 알 수 없어서, 실제 조회/회전은 "토큰(해시) → 소유자
 * 정보"인 기본 레코드로 처리한다. 그런데 로그아웃/재사용 의심 시 "이 회원의 현재 토큰을 찾아서
 * 지운다"처럼 반대 방향(회원 → 토큰) 조회도 여전히 필요해서, 기본 레코드와 별개로 role:id →
 * 현재 토큰 해시를 가리키는 보조 인덱스를 하나 더 둔다. 보조 인덱스는 원자적 CAS의 대상이
 * 아니라 조회 편의를 위한 포인터일 뿐이다 — 실제 회전(rotate)의 원자성은 기본 레코드에 대한
 * Lua 스크립트가 보장한다.
 *
 * (2026-08-19 추가) 회전 성공 시 옛 기본 레코드를 곧바로 DEL 하면, 그 죽은 토큰이 재생(replay)
 * 됐을 때 소유자를 알 방법이 없어 "재사용 탐지"는 되지만 "그 회원의 다른 세션을 강제 종료"하는
 * 부가 조치가 불가능했다. 그래서 DEL 대신 tombstone("|REVOKED" 마커를 붙여 남겨두기)을 쓴다 —
 * 마커의 유효기간은 옛 키에 원래 남아있던 TTL을 그대로 재사용한다(refresh_token_rotate.lua의
 * PTTL 참고) — 1회용 토큰이 원래 유효했을 남은 시간만큼은 재사용 탐지도 살아있어야 하기 때문이다.
 * compareAndRotate()의 반환값이 그래서 Optional 하나가 아니라 SUCCESS/NOT_FOUND/REUSE_DETECTED
 * 셋을 구분하는 RotateOutcome이다.
 *
 * (2026-08-19 추가) 보조 인덱스(activeKey)가 Redis 축출/재시작 등으로 유실되면 findActiveHash()가
 * 빈 값을 반환한다 — 이 경우 호출부가 DB 백업(Member.refreshTokenHash)에서 해시를 구해
 * revokeIfActiveHashMatches() 또는 deleteByHash()를 호출해야 한다. 이 클래스는 그 폴백을 스스로 하지 않는다(Member를 몰라야
 * 하므로) — MemberTokenService.revoke() 참고.
 */
@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {

    private static final String KEY_PREFIX = "refreshToken:";
    private static final String ACTIVE_KEY_PREFIX = "activeRefreshToken:";
    private static final String FIELD_DELIMITER = "\\|";
    private static final String REVOKED_SUFFIX = "|REVOKED";

    private static final RedisScript<String> ROTATE_SCRIPT = loadRotateScript();
    private static final RedisScript<Long> REVOKE_SCRIPT = loadRevokeScript();
    private static final RedisScript<Long> DELETE_ACTIVE_KEY_IF_MATCHES_SCRIPT = loadDeleteActiveKeyIfMatchesScript();

    private static RedisScript<String> loadRotateScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_rotate.lua"));
        script.setResultType(String.class);
        return script;
    }

    private static RedisScript<Long> loadRevokeScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/refresh_token_revoke.lua"));
        script.setResultType(Long.class);
        return script;
    }

    private final StringRedisTemplate redisTemplate;

    /** 로그인/온보딩 발급 시 새 리프레시 토큰을 저장한다. */
    public void save(String refreshToken, Long memberId, String role, TokenType type, boolean remember, Duration ttl) {
        String hash = TokenHasher.sha256(refreshToken);
        redisTemplate.opsForValue().set(primaryKey(hash), serialize(memberId, role, type, remember), ttl);
        redisTemplate.opsForValue().set(activeKey(role, memberId), hash, ttl);
    }

    /** @return 저장된 값이 있으면 그 소유자 정보. 없거나 만료됐으면(=우리가 발급한 적 없으면) empty. */
    public Optional<RefreshTokenData> find(String refreshToken) {
        String value = redisTemplate.opsForValue().get(primaryKey(TokenHasher.sha256(refreshToken)));
        return value == null ? Optional.empty() : Optional.of(parse(value));
    }

    /**
     * 원자적 회전(로테이션). oldRefreshToken 자리의 레코드를 newRefreshToken 자리로 옮기고
     * old는 tombstone(짧게 |REVOKED로 표시)으로 남긴다(기본 레코드는 Lua로 원자적으로 처리).
     * 보조 인덱스(회원 → 현재 토큰)는 그 원자적 연산의 대상이 아니라 회전 성공 시에만 바로 이어서
     * 갱신한다 — 동시에 같은 값을 두고 경쟁하는 다른 요청이 없어서(기본 레코드 CAS가 이미 승자를
     * 하나로 정한 뒤라) 원자성이 없어도 안전하다.
     *
     * @return SUCCESS(정상 회전, data는 새 소유자 정보) / NOT_FOUND(우리가 발급한 적 없거나
     *         tombstone까지 지나 완전히 사라짐) / REUSE_DETECTED(이미 tombstone된 토큰의 재사용
     *         — data에 소유자 정보가 있으니 호출부가 그 회원의 세션을 강제 종료해야 한다)
     */
    public RotateOutcome compareAndRotate(String oldRefreshToken, String newRefreshToken, Duration ttl) {
        String oldHash = TokenHasher.sha256(oldRefreshToken);
        String newHash = TokenHasher.sha256(newRefreshToken);

        String value = redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(primaryKey(oldHash), primaryKey(newHash)),
                String.valueOf(ttl.toMillis())
        );
        if (value == null) {
            return RotateOutcome.notFound();
        }
        if (value.endsWith(REVOKED_SUFFIX)) {
            RefreshTokenData data = parse(value.substring(0, value.length() - REVOKED_SUFFIX.length()));
            return RotateOutcome.reuseDetected(data);
        }

        RefreshTokenData data = parse(value);
        redisTemplate.opsForValue().set(activeKey(data.role(), data.memberId()), newHash, ttl);
        return RotateOutcome.success(data);
    }

    /**
     * 이 회원의 현재 리프레시 토큰 해시(보조 인덱스 기준). 로그아웃/탈퇴/웹훅/재사용 탐지 후
     * 세션 강제 종료 때 "뭘 지워야 하는지" 알아내는 데 쓴다. 보조 인덱스가 유실됐으면(Redis
     * 축출/재시작) empty — 이 경우 호출부가 DB 백업으로 폴백해야 한다.
     */
    public Optional<String> findActiveHash(String role, Long id) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(activeKey(role, id)));
    }

    /**
     * 토큰 기본 레코드는 항상 지우고, activeKey는 지금도 이 해시를 가리킬 때만 함께 지운다.
     * 실패한 옛 revoke를 나중에 재시도하는 사이 새 로그인/재발급으로 activeKey가 다른 해시를 가리킬 수
     * 있으므로, 두 삭제를 Lua로 원자 처리해 새 세션의 포인터를 지우지 않는다.
     */
    public void revokeIfActiveHashMatches(String tokenHash, String role, Long id) {
        redisTemplate.execute(
                REVOKE_SCRIPT,
                List.of(primaryKey(tokenHash), activeKey(role, id)),
                tokenHash);
    }

    /** 삭제 타임아웃 뒤 실제 기본 레코드가 남았는지 후속 확인할 때 사용한다. */
    public boolean existsByHash(String tokenHash) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(primaryKey(tokenHash)));
    }

    /** 해시를 이미 알 때(보조 인덱스에서 구했든, 호출부가 DB 백업에서 구했든) 그 진짜 레코드를 지운다. */
    public void deleteByHash(String tokenHash) {
        redisTemplate.delete(primaryKey(tokenHash));
    }

    /** 보조 인덱스 자체를 지운다. */
    public void deleteActiveKey(String role, Long id) {
        redisTemplate.delete(activeKey(role, id));
    }

    /**
     * 보조 인덱스가 아직 expectedHash를 가리키고 있을 때만 삭제한다.
     *
     * Rotation 이후 DB 확정에 실패했을 때 보상 처리용으로 사용한다.
     * 그 사이 다른 로그인/재발급으로 activeKey가 더 최신 hash를 가리키게 됐다면 삭제하지 않는다.
     *
     * @return 실제로 삭제했으면 true, 이미 다른 hash를 가리키거나 없으면 false
     */
    public boolean deleteActiveKeyIfMatches(
            String role,
            Long id,
            String expectedHash) {

        Long deleted = redisTemplate.execute(
                DELETE_ACTIVE_KEY_IF_MATCHES_SCRIPT,
                List.of(activeKey(role, id)),
                expectedHash
        );

        return deleted != null && deleted == 1L;
    }

    private String primaryKey(String tokenHash) {
        return KEY_PREFIX + tokenHash;
    }

    private String activeKey(String role, Long id) {
        return ACTIVE_KEY_PREFIX + role + ":" + id;
    }

    private String serialize(Long memberId, String role, TokenType type, boolean remember) {
        return memberId + "|" + role + "|" + type.name() + "|" + remember;
    }

    private RefreshTokenData parse(String raw) {
        String[] parts = raw.split(FIELD_DELIMITER, 4);
        return new RefreshTokenData(Long.valueOf(parts[0]), parts[1], TokenType.valueOf(parts[2]), Boolean.parseBoolean(parts[3]));
    }

    public record RefreshTokenData(Long memberId, String role, TokenType type, boolean remember) {
    }

    private static RedisScript<Long> loadDeleteActiveKeyIfMatchesScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText("""
            local current = redis.call('GET', KEYS[1])

            if current == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end

            return 0
            """);
        script.setResultType(Long.class);
        return script;
    }

    /** compareAndRotate()의 3단 결과. Optional 하나로는 "없음"과 "재사용 의심(소유자는 앎)"을 구분 못 해서 뺐다. */
    public record RotateOutcome(Status status, RefreshTokenData data) {

        public enum Status { SUCCESS, NOT_FOUND, REUSE_DETECTED }

        public static RotateOutcome success(RefreshTokenData data) {
            return new RotateOutcome(Status.SUCCESS, data);
        }

        public static RotateOutcome notFound() {
            return new RotateOutcome(Status.NOT_FOUND, null);
        }

        public static RotateOutcome reuseDetected(RefreshTokenData data) {
            return new RotateOutcome(Status.REUSE_DETECTED, data);
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isReuseDetected() {
            return status == Status.REUSE_DETECTED;
        }
    }
}