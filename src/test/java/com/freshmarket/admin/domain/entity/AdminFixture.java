package com.freshmarket.admin.domain.entity;

import java.time.LocalDateTime;

public final class AdminFixture {

    // 프로덕션과 동일하게 Admin.register() 팩터리를 사용해 테스트용 관리자를 생성한다.
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