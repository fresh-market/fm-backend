package com.freshmarket.member.internal.repository;

import com.freshmarket.member.internal.entity.Address;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// (2026-08-18 10:49) com.freshmarket.address.domain.repository에서 이동.
public interface AddressRepository extends JpaRepository<Address, Long> {

    // (2026-08-18 18:40) docs/api/member.md: "기본 배송지가 먼저 온다." 원래
    // findByMemberIdOrderByCreatedAtDesc는 등록 순서로만 정렬해 이 규칙을 안 지켰다(API 점검 중
    // 발견). isDefault를 정렬 파생 메서드 이름에 넣지 않고 @Query로 명시한 이유: 이 필드가
    // Lombok이 isDefault() 게터를 만드는 boolean이라 파생 쿼리 이름 파싱이 "default"로
    // 잘못 해석할 수 있는 알려진 함정이 있다 — JPQL로 쓰면 그 위험이 없다.
    @Query("select a from Address a where a.memberId = :memberId order by a.isDefault desc, a.createdAt desc")
    List<Address> findByMemberIdOrderedByDefaultFirst(@Param("memberId") Long memberId);

    // (2026-08-20, API-3-04/API-5-01) 컨트롤러의 목록 응답용. 배송지가 회원당 10개(등록 상한)로
    // 작아서 지금 당장 성능 문제는 아니지만, 기존 메서드에 나중에 페이지네이션을 끼워 넣는 건
    // 호환을 깨는 변경이라(api-design-guideline.md) 처음부터 넣어둔다. 정렬 기준은 위 메서드와
    // 동일하게 유지한다.
    @Query("select a from Address a where a.memberId = :memberId order by a.isDefault desc, a.createdAt desc")
    Page<Address> findByMemberIdOrderedByDefaultFirst(@Param("memberId") Long memberId, Pageable pageable);

    Optional<Address> findByIdAndMemberId(Long id, Long memberId);

    long countByMemberId(Long memberId);

    @Modifying
    @Query("update Address a set a.isDefault = false where a.memberId = :memberId and a.isDefault = true")
    void clearDefaultForMember(Long memberId);
}
