package com.freshmarket.admin.domain.entity;

import java.time.LocalDateTime;

public final class AdminFixture {

    // Admin 은 관리자 등록(계정 발급) 기능이 아직 없어 public 팩터리가 없다 (EC-3-08).
    // 같은 패키지의 패키지 전용 생성자를 직접 호출한다 (Admin.java 주석 참고).
    public static Admin active(String loginId, String passwordHash, AdminRole role) {
        return new Admin(loginId, passwordHash, "테스트관리자", role);
    }

    public static Admin inactive(String loginId, String passwordHash, AdminRole role) {
        Admin admin = active(loginId, passwordHash, role);
        admin.deactivate(LocalDateTime.now());
        return admin;
    }

    private AdminFixture() {
    }
}