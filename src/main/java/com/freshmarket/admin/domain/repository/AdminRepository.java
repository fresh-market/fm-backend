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

    // 로그아웃의 Refresh Token 폐기를 같은 관리자 행의 다른 갱신과 직렬화한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    Optional<Admin> findByIdForUpdate(@Param("id") Long id);

    // 지연 재시도 전용 조건부 폐기. 실패 당시의 해시와 현재 DB 해시가 같을 때만 지운다.
    // 재시도 전에 관리자가 다시 로그인해 새 Refresh Token이 발급됐다면 현재 해시가 달라지므로
    // rows-affected 0으로 끝나고 새 세션의 Refresh Token은 건드리지 않는다.
    @Modifying
    @Query("update Admin a set a.refreshTokenHash = null, a.refreshTokenExpiresAt = null "
            + "where a.id = :id and a.refreshTokenHash = :hash")
    int clearRefreshTokenIfMatches(@Param("id") Long id, @Param("hash") String hash);
}