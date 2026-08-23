package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMemberId(Long memberId);
}
