package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.Admin;

import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    Optional<Admin> findByLoginId(String loginId);

    // Redis가 완전히 장애 난 경우 opaque Refresh Token의 SHA-256 해시로 관리자를 역조회한다.
    Optional<Admin> findByRefreshTokenHash(String refreshTokenHash);

    // Redis 장애 시 DB 백업만으로 Rotation을 수행하기 위한 조건부 갱신(CAS).
    @Modifying
    @Query("update Admin a set a.refreshTokenHash = :newHash, a.refreshTokenExpiresAt = :expiresAt "
            + "where a.id = :id and a.refreshTokenHash = :oldHash")
    int compareAndSetRefreshToken(
            @Param("id") Long id,
            @Param("oldHash") String oldHash,
            @Param("newHash") String newHash,
            @Param("expiresAt") LocalDateTime expiresAt);
}