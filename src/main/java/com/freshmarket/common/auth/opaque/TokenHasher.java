package com.freshmarket.common.auth.opaque;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * refreshToken처럼 "저장은 해시로 해두고, 나중엔 비교만 하면 되는" 고엔트로피 랜덤 토큰을 해싱하는 유틸.
 * 비밀번호(PasswordEncoder/bcrypt)와 달리 SHA-256을 쓰는 이유: JWT 서명 자체가 이미 고엔트로피라
 * 브루트포스가 비현실적이라, 굳이 느린 해시를 쓸 이유가 없다.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 찾을 수 없음", e);
        }
    }
}
