package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMemberId(Long memberId);

    List<KakaoUnlinkFailure> findByAttemptCountLessThanAndResolvedFalse(int threshold);

    List<KakaoUnlinkFailure> findByAttemptCountGreaterThanEqualAndResolvedFalse(int threshold);
}
