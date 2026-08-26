package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.AdminAuditFailure;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminAuditFailureRepository extends JpaRepository<AdminAuditFailure, Long> {

    List<AdminAuditFailure> findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from AdminAuditFailure f where f.id = :id")
    Optional<AdminAuditFailure> findByIdForUpdate(@Param("id") Long id);
}