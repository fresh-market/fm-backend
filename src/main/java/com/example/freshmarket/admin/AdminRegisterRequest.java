package com.example.freshmarket.admin;

import jakarta.validation.constraints.NotBlank;

public record AdminRegisterRequest(@NotBlank String loginId,
                                   @NotBlank String password,
                                   @NotBlank String name) {


    public Admin toEntity(String encodedPassword) {
        return Admin.builder()
                .loginId(this.loginId)
                .passwordHash(encodedPassword)
                .name(this.name)
                .role(AdminRole.ADMIN)
                .build();
    }
}