package com.freshmarket.admin.internal.entity;

import java.time.LocalDateTime;

public final class AdminFixture {

    // 관리자 테스트 데이터는 실제 관리자 등록과 동일하게 public 팩터리인 Admin.register()를 사용해 생성한다.
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