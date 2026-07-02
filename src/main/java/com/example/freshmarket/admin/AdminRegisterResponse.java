package com.example.freshmarket.admin;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AdminRegisterResponse(Long id,
                                    String loginId,
                                    String password,
                                    String name,
                                    AdminRole role,
                                    LocalDateTime createdAt) {

    static public AdminRegisterResponse from(Admin admin) {
        return AdminRegisterResponse.builder()
                .id(admin.getId())
                .loginId(admin.getLoginId())
                .name(admin.getName())
                .role(admin.getRole())
                .createdAt(admin.getCreatedAt())
                .build();
    }
}
