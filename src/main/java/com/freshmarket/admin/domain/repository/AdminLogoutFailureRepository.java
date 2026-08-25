package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.AdminLogoutFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminLogoutFailureRepository extends JpaRepository<AdminLogoutFailure, Long> {

    Optional<AdminLogoutFailure> findByAdminId(Long adminId);

    List<AdminLogoutFailure> findByResolvedFalse();
}