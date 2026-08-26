package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRevokeFailureRepository extends JpaRepository<RefreshTokenRevokeFailure, Long> {

    Optional<RefreshTokenRevokeFailure> findByMemberIdAndRefreshTokenHash(Long memberId, String refreshTokenHash);

    Optional<RefreshTokenRevokeFailure> findByIdAndRefreshTokenHash(Long id, String refreshTokenHash);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("""
            delete from RefreshTokenRevokeFailure f
            where f.id = :id and f.refreshTokenHash = :refreshTokenHash
            """)
    int deleteByIdAndRefreshTokenHash(
            @org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("refreshTokenHash") String refreshTokenHash);
}
