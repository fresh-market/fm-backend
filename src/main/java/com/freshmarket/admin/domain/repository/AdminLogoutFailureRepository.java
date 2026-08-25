package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.AdminLogoutFailure;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AdminLogoutFailureRepository extends JpaRepository<AdminLogoutFailure, Long> {

    Optional<AdminLogoutFailure> findByAdminId(Long adminId);

    /**
     * 한 번에 최대 100건만 읽고 PK 커서로 다음 청크를 이어간다.
     * 처리 결과가 resolved=true로 바뀌어도 offset을 쓰지 않으므로 중간 행을 건너뛰지 않는다.
     */
    List<AdminLogoutFailure> findTop100ByResolvedFalseAndIdGreaterThanOrderByIdAsc(Long id);

    /**
     * 외부 Redis 호출 전에 실패 건을 원자적으로 선점한다. 다른 인스턴스가 이미 선점했다면 0,
     * 내가 선점했으면 1을 반환한다. 프로세스가 중간에 죽어도 lease가 지난 선점은 다시 가져갈 수 있다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AdminLogoutFailure f
               set f.processing = true,
                   f.processingStartedAt = :claimedAt,
                   f.updatedAt = :claimedAt
             where f.id = :failureId
               and f.resolved = false
               and (f.processing = false
                    or f.processingStartedAt is null
                    or f.processingStartedAt < :staleBefore)
            """)
    int claimForRetry(
            @Param("failureId") Long failureId,
            @Param("claimedAt") LocalDateTime claimedAt,
            @Param("staleBefore") LocalDateTime staleBefore);
}