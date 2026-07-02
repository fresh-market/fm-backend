package com.example.freshmarket.admin;

import com.example.freshmarket.common.response.SuccessResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAdminService adminService;

    // ============== Admin ==============

    @PostMapping("/login")
    public SuccessResponse<AdminLoginResponse> login(@RequestBody @Valid AdminLoginRequest request) {
        AdminLoginResponse response = adminService.login(request.loginId(), request.password());
        return SuccessResponse.of(response);
    }

    @PostMapping("/{superAdminId}")
    // Todo: Security 구현 전까지 Pathvariable로 superAdminId 받음
    public SuccessResponse<AdminRegisterResponse> register(
            @RequestBody @Valid AdminRegisterRequest request,
            @PathVariable Long superAdminId) {
        AdminRegisterResponse response = adminService.register(request, superAdminId);
        return SuccessResponse.of(response);
    }

    // 관리자 -> 관리자 역할 수정 애매해서 주석처리함
//    @PatchMapping("/{adminId}/role")
//    public SuccessResponse<Void> updateRole(@PathVariable Long adminId) {
//
//
//        return SuccessResponse.of(null);
//    }

    // Todo: Security 구현 전까지 Pathvariable로 superAdminId 받음
    @DeleteMapping("/{adminId}/{superAdminId}")
    public SuccessResponse<Void> deleteAdmin(@PathVariable Long adminId,
                            @PathVariable Long superAdminId) {

        adminService.deleteAdmin(adminId, superAdminId);

        return SuccessResponse.of(null);
    }
}
