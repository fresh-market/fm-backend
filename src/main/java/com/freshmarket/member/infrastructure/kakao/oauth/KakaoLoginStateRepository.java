package com.freshmarket.member.infrastructure.kakao.oauth;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

// (2026-08-18 12:10) docs/api/auth.md 기준 프론트 콜백형 로그인 흐름을 위해 신설.
/**
 * "GET /v1/auth/kakao/authorize"에서 발급한 state를 nonce와 함께 잠깐(TTL 5분) 저장해뒀다가,
 * "POST /v1/auth/tokens"에서 되돌아온 state가 우리가 실제로 발급한 것인지, 그리고 그때 같이
 * 넣었던 nonce가 무엇이었는지 확인하는 데 쓴다.
 *
 * 예전(서버 리다이렉트형) 방식에서는 Spring Security가 이 역할을 HttpSession에 저장하는 걸로
 * 대신했었다 — 여기서는 세션 대신 Redis를 쓴다(이 API가 stateless라 세션에 기대고 싶지 않다).
 *
 * 한 번 쓰고 버리는 값이라 조회 즉시 삭제한다(재사용 방지 — authorization code처럼 재생 공격의
 * 대상이 될 수 있다).
 */
@Repository
@RequiredArgsConstructor
public class KakaoLoginStateRepository {

    private static final String KEY_PREFIX = "kakaoLoginState:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    public void save(String state, String nonce) {
        redisTemplate.opsForValue().set(KEY_PREFIX + state, nonce, TTL);
    }

    // (2026-08-19) GET 후 DELETE로 나눠 부르면 그 사이가 원자적이지 않아, 같은 state로 동시에
    // 들어온 두 요청이 모두 같은 nonce를 읽어갈 수 있었다(DI-2-01) — "한 번 쓰고 버린다"는 클래스
    // 설명과 어긋남. Redis의 GETDEL(단일 명령이라 서버 쪽에서 원자적으로 처리됨)로 교체해
    // 조회와 삭제를 하나의 원자적 연산으로 묶는다. 리프레시 토큰 로테이션에 쓴 Lua CAS와 같은
    // 목적(원자적 read-and-invalidate)을 더 가벼운 방식으로 만족시킨다.
    /** @return 저장돼 있던 nonce. 이미 소비됐거나 만료됐으면(=state가 우리 것이 아니면) null. */
    public String consume(String state) {
        return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + state);
    }
}
