package com.freshmarket.admin.domain.service;

import com.freshmarket.admin.domain.entity.Admin;
import com.freshmarket.admin.domain.entity.AdminRole;
import com.freshmarket.admin.domain.exception.AdminTokenErrorCode;
import com.freshmarket.admin.domain.exception.AdminException;
import com.freshmarket.admin.domain.repository.AdminTokenRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 Refresh Token의 DB 검증/회전만 짧은 트랜잭션으로 처리한다.
 * Redis I/O는 AdminTokenService에서 수행해 DB 트랜잭션과 섞지 않는다.
 */
@Service
@RequiredArgsConstructor
public class AdminTokenTransactionService {

    private final AdminTokenRepository adminRepository;

    @Transactional
    public RotationState rotateKnownAdmin(
            Long adminId,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(this::invalidRefreshToken);
        return rotate(admin, oldHash, newHash, newExpiresAt, now);
    }

    @Transactional
    public RotationState rotateByHash(
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now) {
        Admin admin = adminRepository.findByRefreshTokenHash(oldHash)
                .orElseThrow(this::invalidRefreshToken);
        return rotate(admin, oldHash, newHash, newExpiresAt, now);
    }

    private RotationState rotate(
            Admin admin,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now) {
        if (!admin.isActive()
                || admin.getRefreshTokenHash() == null
                || !admin.getRefreshTokenHash().equals(oldHash)
                || admin.getRefreshTokenExpiresAt() == null
                || !admin.getRefreshTokenExpiresAt().isAfter(now)) {
            throw invalidRefreshToken();
        }

        int updated = adminRepository.compareAndSetRefreshToken(
                admin.getId(), oldHash, newHash, newExpiresAt);
        if (updated != 1) {
            throw invalidRefreshToken();
        }

        return new RotationState(admin.getId(), admin.getRole());
    }

    private AdminException invalidRefreshToken() {
        return new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    public record RotationState(Long adminId, AdminRole role) {
    }
}