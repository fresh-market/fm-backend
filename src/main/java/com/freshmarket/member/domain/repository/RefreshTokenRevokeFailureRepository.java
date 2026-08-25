package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.RefreshTokenRevokeFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRevokeFailureRepository extends JpaRepository<RefreshTokenRevokeFailure, Long> {

    Optional<RefreshTokenRevokeFailure> findByMemberId(Long memberId);
}
