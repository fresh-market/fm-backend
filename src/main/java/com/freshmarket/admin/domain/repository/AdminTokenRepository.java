package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.Admin;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface AdminTokenRepository extends Repository<Admin, Long> {

    Optional<Admin> findById(Long id);

    Optional<Admin> findByRefreshTokenHash(String refreshTokenHash);

    @Modifying
    @Query("update Admin a set a.refreshTokenHash = :newHash, a.refreshTokenExpiresAt = :expiresAt "
            + "where a.id = :id and a.refreshTokenHash = :oldHash")
    int compareAndSetRefreshToken(
            @Param("id") Long id,
            @Param("oldHash") String oldHash,
            @Param("newHash") String newHash,
            @Param("expiresAt") LocalDateTime expiresAt);
}