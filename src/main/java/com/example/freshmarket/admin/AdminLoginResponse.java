package com.example.freshmarket.admin;

public record AdminLoginResponse(Long adminId, String name, AdminRole role) {

    public static AdminLoginResponse from(Admin admin) {
        return new AdminLoginResponse(admin.getId(), admin.getName(), admin.getRole());
    }
}
