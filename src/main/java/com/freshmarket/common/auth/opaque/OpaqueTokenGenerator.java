package com.freshmarket.common.auth.opaque;

import java.security.SecureRandom;
import java.util.Base64;

/*
 * (2026-08-19) feat/admin-login 브랜치의 common.security.OpaqueTokenGenerator를 그대로 가져왔다 —
 * 리프레시 토큰을 JWT가 아니라 불투명 문자열로 바꾸면서(SEC-1-04 정리 겸) 그쪽이 이미 만들어둔
 * 걸 재사용한다. 클레임을 담을 이유가 없다(서버가 해시로 조회할 뿐이다) — 파싱 가능한 형식은
 * 위조 표면만 늘린다.
 */
public final class OpaqueTokenGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;

    private OpaqueTokenGenerator() {
    }

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
