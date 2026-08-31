package com.freshmarket.admin.internal.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AdminRole {

    ADMIN("관리자"),
    SUPER_ADMIN("최고관리자");

    private final String displayName;

    // (merge: feat/member-auth와 합치며 추가) JwtTokenProvider가 만드는 JWT의 role 클레임은
    // Spring Security 권한 문자열 그대로("ROLE_ADMIN", "ROLE_SUPER_ADMIN")를 담는다 — enum
    // 상수명 자체가 이미 그 포맷인 MemberRole(ROLE_USER)과 형식을 맞춘다.
    public String toAuthority() {
        return "ROLE_" + name();
    }
}