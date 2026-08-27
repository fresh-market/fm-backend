package com.freshmarket.admin.domain.entity;

import java.time.LocalDateTime;

public final class AdminFixture {

    // 관리자 테스트 데이터도 운영 코드와 같은 공개 팩터리를 사용해 생성 규칙을 우회하지 않는다.
    public static Admin active(String loginId, String passwordHash, AdminRole role) {
        return Admin.register(loginId, passwordHash, "테스트관리자", role);
    }

    public static Admin inactive(String loginId, String passwordHash, AdminRole role) {
        Admin admin = active(loginId, passwordHash, role);
        admin.deactivate(LocalDateTime.now());
        return admin;
    }

    private AdminFixture() {
    }
}