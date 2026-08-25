package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.Admin;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminRepository extends JpaRepository<Admin, Long> {

    /** 로그인과 로그아웃이 같은 관리자 토큰 상태를 동시에 덮어쓰지 않도록 행 잠금을 건다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Admin> findByLoginId(String loginId);

    /** 로그아웃의 Refresh Token 폐기를 같은 관리자 행의 다른 갱신과 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Admin a where a.id = :id")
    Optional<Admin> findByIdForUpdate(@Param("id") Long id);
}