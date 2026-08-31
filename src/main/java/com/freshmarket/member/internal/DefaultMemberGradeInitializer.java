package com.freshmarket.member.internal;

import com.freshmarket.member.internal.entity.MemberGrade;
import com.freshmarket.member.internal.repository.MemberGradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * member.member_grade_id가 NOT NULL FK라, 회원가입이 되려면 member_grade에 isDefault=true인
 * 행이 최소 1개는 있어야 한다 — 없으면 가입 자체가 막힌다.
 *
 * "최대 1개"는 member_grade.is_default_key(생성 컬럼) + UNIQUE로 DB가 강제하지만, "최소 1개
 * 존재"는 DB 제약만으로 표현할 수 없는 조건이라 이 초기화기가 그 역할을 진다.
 *
 * domain.service가 아니라 internal 바로 아래에 둔 이유: 기동 시 시드하는 초기화기라 요청 단위로
 * 호출되는 서비스가 아니고, internal.service 패키지는 100% 커버리지 게이트 대상이라 성격이
 * 다른 이 클래스를 거기 두면 게이트 취지에도 안 맞는다.
 *
 * (2026-08-18 10:49) com.freshmarket.membergrade.domain에서 이동 — domain-map.md 기준
 * member_grade는 member 도메인 소유 테이블이라 membergrade를 별도 최상위 도메인으로 둘 이유가
 * 없었다. 로직 변경 없음, 패키지만 member.domain으로 옮김.
 *
 * (2026-08-18 16:40) 원래는 "Flyway는 테이블 구조만 정의하고 시드 데이터는 안 넣는다"는 전제로
 * 이 클래스가 기본 등급을 직접 만들었는데(임시로 "일반"이라는 이름을 썼던 거라
 * docs/api/member.md가 명시한 "브론즈/실버/골드"와 안 맞았다), 이제
 * V2__seed_member_grades.sql이 그 세 등급(브론즈를 기본값으로)을 직접 심는다 — 스키마와 초기
 * 데이터를 둘 다 Flyway가 소유하는 게 이 프로젝트 컨벤션(V1 참고)과 맞다. 그래서 이 클래스는
 * 평소엔 findByIsDefaultTrue()에서 바로 걸려 아무 일도 안 하고, 마이그레이션이 어떤 이유로든
 * 안 먹혔거나 기본 등급이 운영 중 실수로 전부 지워진 극단적인 경우에만 동작하는 자가치유
 * 안전장치로 성격이 바뀌었다. 그때 쓸 이름도 "일반"이 아니라 문서가 정한 최하위 등급인
 * "브론즈"로 맞췄다.
 *
 * [운영 참고] V2 마이그레이션은 INSERT라 기존에 이미 "일반"으로 시딩돼 있던 로컬/개발 DB의
 * 행을 지우지 않는다 — 그대로 두면 member_grade에 "일반"(is_default=true)과 "브론즈"가
 * 같이 남아 findByIsDefaultTrue()가 여전히 "일반"을 돌려준다. 이 문제를 겪은 로컬 DB는
 * 마이그레이션 적용 후 `DELETE FROM member_grade WHERE name = '일반'`로 수동 정리했다
 * (2026-08-18, 참조 무결성상 해당 등급을 쓰는 회원이 없는 상태에서만 안전하다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultMemberGradeInitializer implements ApplicationRunner {

    private static final String DEFAULT_GRADE_NAME = "브론즈";

    private final MemberGradeRepository memberGradeRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberGradeRepository.findByIsDefaultTrue().isPresent()) {
            return;
        }

        if (memberGradeRepository.existsByName(DEFAULT_GRADE_NAME)) {
            log.warn("event=DEFAULT_MEMBER_GRADE_SEED_SKIPPED reason=NAME_EXISTS_BUT_NOT_DEFAULT name={}", DEFAULT_GRADE_NAME);
            return;
        }

        memberGradeRepository.save(MemberGrade.register(DEFAULT_GRADE_NAME, null, true));
        log.info("event=DEFAULT_MEMBER_GRADE_SEEDED name={}", DEFAULT_GRADE_NAME);
    }
}
