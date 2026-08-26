package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRevokeFailureRepository extends JpaRepository<RefreshTokenRevokeFailure, Long> {

    Optional<RefreshTokenRevokeFailure> findByMemberIdAndRefreshTokenHash(Long memberId, String refreshTokenHash);

    Optional<RefreshTokenRevokeFailure> findByIdAndRefreshTokenHash(Long id, String refreshTokenHash);

    @Modifying
    @Query("""
            delete from RefreshTokenRevokeFailure f
            where f.id = :id and f.refreshTokenHash = :refreshTokenHash
            """)
    int deleteByIdAndRefreshTokenHash(
            @Param("id") Long id,
            @Param("refreshTokenHash") String refreshTokenHash);

    /**
     * 동일 회원·토큰 해시의 실패 기록을 원자적으로 만든다. 유니크 충돌을 Java에서 복구하지 않아도
     * 되므로, IDENTITY INSERT 실패 뒤 같은 영속성 컨텍스트를 계속 쓰는 데 의존하지 않는다.
     */
    @Modifying
    @Query(value = """
            insert into refresh_token_revoke_failure
                (member_id, role, refresh_token_hash, attempt_count, created_at, updated_at)
            values
                (:memberId, :role, :refreshTokenHash, 1, now(6), now(6))
            on duplicate key update
                attempt_count = attempt_count + 1,
                updated_at = now(6)
            """, nativeQuery = true)
    int upsertFailure(
            @Param("memberId") Long memberId,
            @Param("role") String role,
            @Param("refreshTokenHash") String refreshTokenHash);
}
