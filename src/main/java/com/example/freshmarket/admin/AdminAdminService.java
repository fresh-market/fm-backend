package com.example.freshmarket.admin;

public interface AdminAdminService {

    AdminLoginResponse login(String loginId, String password);

    AdminRegisterResponse register(AdminRegisterRequest request, Long adminId);

    // void changeRole(Long targetAdminId, AdminRole newRole, Long requesterId);

    void deleteAdmin(Long targetAdminId, Long requesterId);
}
