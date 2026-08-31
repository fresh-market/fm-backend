package com.freshmarket.admin.internal.repository;

import com.freshmarket.admin.internal.entity.Admin;
import com.freshmarket.admin.internal.entity.AdminAuditLog;
import com.freshmarket.admin.internal.entity.AdminRole;
import com.freshmarket.admin.internal.exception.AdminException;
import com.freshmarket.admin.internal.exception.AdminTokenErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 Refresh Token의 DB 검증/회전 경계.
 * Redis I/O는 서비스에서 수행하고, DB CAS와 성공 감사 로그만 짧은 트랜잭션으로 묶는다.
 */
@Repository
@RequiredArgsConstructor
public class AdminTokenRepository {

    private static final int DB_TRANSACTION_TIMEOUT_SECONDS = 1;

    private final EntityManager entityManager;

    @Transactional(timeout = DB_TRANSACTION_TIMEOUT_SECONDS)
    public RotationState rotateKnownAdmin(
            Long adminId,
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now) {
        Admin admin = entityManager.find(Admin.class, adminId);
        if (admin == null) {
            throw invalidRefreshToken();
        }
        return rotate(admin, oldHash, newHash, newExpiresAt, now);
    }

    @Transactional(readOnly = true, timeout = DB_TRANSACTION_TIMEOUT_SECONDS)
    public Optional<Long> findAdminIdByRefreshTokenHash(String refreshTokenHash) {
        return entityManager.createQuery(
                        "select a.id from Admin a where a.refreshTokenHash = :refreshTokenHash", Long.class)
                .setParameter("refreshTokenHash", refreshTokenHash)
                .getResultStream()
                .findFirst();
    }

    @Transactional(timeout = DB_TRANSACTION_TIMEOUT_SECONDS)
    public RotationState rotateByHash(
            String oldHash,
            String newHash,
            LocalDateTime newExpiresAt,
            LocalDateTime now) {
        Admin admin = entityManager.createQuery(
                        "select a from Admin a where a.refreshTokenHash = :oldHash", Admin.class)
                .setParameter("oldHash", oldHash)
                .getResultStream()
                .findFirst()
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

        Query update = entityManager.createQuery(
                "update Admin a set a.refreshTokenHash = :newHash, a.refreshTokenExpiresAt = :expiresAt "
                        + "where a.id = :id and a.refreshTokenHash = :oldHash");
        int updated = update
                .setParameter("newHash", newHash)
                .setParameter("expiresAt", newExpiresAt)
                .setParameter("id", admin.getId())
                .setParameter("oldHash", oldHash)
                .executeUpdate();
        if (updated != 1) {
            throw invalidRefreshToken();
        }

        // 성공 감사 로그를 DB Rotation과 같은 트랜잭션에 넣어 둘 중 하나만 남는 상태를 만들지 않는다.
        entityManager.persist(AdminAuditLog.of(
                admin.getId(),
                "ADMIN_TOKEN_REISSUE",
                String.valueOf(admin.getId()),
                "result=SUCCESS"));

        return new RotationState(admin.getId(), admin.getRole());
    }

    private AdminException invalidRefreshToken() {
        return new AdminException(AdminTokenErrorCode.REFRESH_TOKEN_INVALID);
    }

    public record RotationState(Long adminId, AdminRole role) {
    }
}