package com.example.freshmarket.admin;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "admin")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Admin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "admin_id")
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AdminRole role;

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Builder
    private Admin(String loginId, String passwordHash, String name, AdminRole role) {
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
    }

    public void updateRole(AdminRole role) {
        this.role = role;
    }
}
