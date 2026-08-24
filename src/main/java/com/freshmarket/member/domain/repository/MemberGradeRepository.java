package com.freshmarket.member.domain.repository;

import com.freshmarket.member.domain.entity.MemberGrade;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

// (2026-08-18 10:49) com.freshmarket.membergrade.domain.repository에서 이동 — member 도메인
// 내부 리포지토리라 더 이상 Api를 경유할 필요가 없다.
public interface MemberGradeRepository extends JpaRepository<MemberGrade, Long> {

    Optional<MemberGrade> findByIsDefaultTrue();

    boolean existsByName(String name);
}
