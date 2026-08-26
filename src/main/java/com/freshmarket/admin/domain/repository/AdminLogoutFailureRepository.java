package com.freshmarket.admin.domain.repository;

import com.freshmarket.admin.domain.entity.AdminLogoutFailure;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface AdminLogoutFailureRepository extends JpaRepository<AdminLogoutFailure, Long> {

    /**
     * 같은 admin_id에 대한 실패 기록 생성/재오픈을 DB 한 문장으로 원자적으로 처리한다.
     * 두 요청이 동시에 들어와도 UNIQUE(admin_id)와 ON DUPLICATE KEY UPDATE가 직렬화하므로
     * "둘 다 없음 조회 -> 둘 다 INSERT" 경쟁으로 유니크 위반이 발생하지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO admin_logout_failure (
                admin_id, refresh_token_hash, redis_failed, db_failed,
                attempt_count, resolved, processing, processing_started_at,
                created_at, updated_at
            ) VALUES (
                :adminId, :refreshTokenHash, :redisFailed, :dbFailed,
                1, FALSE, FALSE, NULL, :now, :now
            )
            ON DUPLICATE KEY UPDATE
                refresh_token_hash =
                    CASE
                        WHEN resolved = TRUE THEN :refreshTokenHash
                        ELSE COALESCE(:refreshTokenHash, refresh_token_hash)
                    END,
                redis_failed =
                    CASE
                        WHEN resolved = TRUE THEN :redisFailed
                        ELSE redis_failed OR :redisFailed
                    END,
                db_failed =
                    CASE
                        WHEN resolved = TRUE THEN :dbFailed
                        ELSE db_failed OR :dbFailed
                    END,
                attempt_count = 1,
                resolved = FALSE,
                processing = FALSE,
                processing_started_at = NULL,
                updated_at = :now
            """, nativeQuery = true)
    void upsertFailure(
            @Param("adminId") Long adminId,
            @Param("refreshTokenHash") String refreshTokenHash,
            @Param("redisFailed") boolean redisFailed,
            @Param("dbFailed") boolean dbFailed,
            @Param("now") LocalDateTime now);

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

    /**
     * 내가 획득한 lease(processing_started_at)가 아직 유효할 때만 재시도 결과를 반영한다.
     * lease 만료 뒤 다른 인스턴스가 재선점했다면 0을 반환해 늦게 끝난 옛 실행자의 덮어쓰기를 막는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AdminLogoutFailure f
               set f.attemptCount = f.attemptCount + 1,
                   f.dbFailed = :dbFailed,
                   f.redisFailed = :redisFailed,
                   f.refreshTokenHash = coalesce(:latestRefreshTokenHash, f.refreshTokenHash),
                   f.resolved = :resolved,
                   f.processing = false,
                   f.processingStartedAt = null,
                   f.updatedAt = :updatedAt
             where f.id = :failureId
               and f.resolved = false
               and f.processing = true
               and f.processingStartedAt = :claimedAt
            """)
    int applyOutcomeIfClaimOwned(
            @Param("failureId") Long failureId,
            @Param("claimedAt") LocalDateTime claimedAt,
            @Param("dbFailed") boolean dbFailed,
            @Param("redisFailed") boolean redisFailed,
            @Param("resolved") boolean resolved,
            @Param("latestRefreshTokenHash") String latestRefreshTokenHash,
            @Param("updatedAt") LocalDateTime updatedAt);

    /** 외부 작업을 시작하지 못했을 때도 내가 가진 lease인 경우에만 선점을 반납한다. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update AdminLogoutFailure f
               set f.processing = false,
                   f.processingStartedAt = null,
                   f.updatedAt = :updatedAt
             where f.id = :failureId
               and f.resolved = false
               and f.processing = true
               and f.processingStartedAt = :claimedAt
            """)
    int releaseClaimIfOwned(
            @Param("failureId") Long failureId,
            @Param("claimedAt") LocalDateTime claimedAt,
            @Param("updatedAt") LocalDateTime updatedAt);
}