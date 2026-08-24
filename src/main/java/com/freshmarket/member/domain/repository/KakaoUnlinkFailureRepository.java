package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.KakaoUnlinkFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface KakaoUnlinkFailureRepository extends JpaRepository<KakaoUnlinkFailure, Long> {

    Optional<KakaoUnlinkFailure> findByMemberId(Long memberId);

    List<KakaoUnlinkFailure> findByAttemptCountLessThanAndResolvedFalse(int threshold);

    long countByAttemptCountGreaterThanEqualAndResolvedFalse(int threshold);

    List<KakaoUnlinkFailure>
    findTop50ByAttemptCountGreaterThanEqualAndResolvedFalseOrderByCreatedAtAscIdAsc(int threshold);

    Page<KakaoUnlinkFailure> findByAttemptCountGreaterThanEqualAndResolvedFalse(
            int threshold, Pageable pageable);
}
