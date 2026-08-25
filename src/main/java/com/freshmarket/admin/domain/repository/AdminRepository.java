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

    /*
     * Redis 완전 장애 시 로그인 자체가 막히지 않도록, Refresh Token 발급 시 DB에도 write-through로
     * 백업한다 (RefreshTokenRepository 클래스 주석, MemberRepository.updateRefreshToken() 참고).
     * 엔티티를 로드해 dirty checking에 기대는 대신 벌크 UPDATE로 직접 반영한다 — AdminAuthService.login()이
     * 아직 이 목적만으로 트랜잭션을 열고 있지 않기 때문이다.
     */
    @Modifying
    @Query("update Admin a set a.refreshTokenHash = :hash, a.refreshTokenExpiresAt = :expiresAt where a.id = :id")
    int updateRefreshToken(@Param("id") Long id, @Param("hash") String hash, @Param("expiresAt") LocalDateTime expiresAt);
}