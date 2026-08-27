package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.Admin;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    // 로그인 자격 증명 검증용 조회. BCrypt 비교 중에는 DB 쓰기 잠금을 잡지 않는다.
    Optional<Admin> findByLoginId(String loginId);

    // 로그인/로그아웃의 Refresh Token 갱신이 같은 관리자 행에서 겹치지 않도록 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    Optional<Admin> findByIdForUpdate(@Param("id") Long id);

    // 로그인 Redis 저장 실패 보상용. 이번 로그인에서 기록한 해시가 아직 현재 값일 때만 DB 백업을 제거한다.
    @Modifying
    @Query("update Admin a set a.refreshTokenHash = null, a.refreshTokenExpiresAt = null "
            + "where a.id = :id and a.refreshTokenHash = :hash")
    int clearRefreshTokenIfMatches(@Param("id") Long id, @Param("hash") String hash);
}