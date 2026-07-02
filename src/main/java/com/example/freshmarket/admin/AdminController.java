package com.example.freshmarket.admin;

import com.example.freshmarket.common.response.SuccessResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminController {

    private final AdminAdminService adminService;

    // ============== Admin ==============

    @PostMapping("/login")
    public SuccessResponse<AdminLoginResponse> login(@RequestBody @Valid AdminLoginRequest request) {
        AdminLoginResponse response = adminService.login(request.loginId(), request.password());
        return SuccessResponse.of(response);
    }
}
