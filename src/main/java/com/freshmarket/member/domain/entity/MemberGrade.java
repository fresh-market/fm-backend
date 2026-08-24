package com.freshmarket.member.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 등급(단골 구분용). member_grade 테이블(V1__init_schema.sql)에 대응한다.
 *
 * member.memberGradeId가 이 표를 NOT NULL FK로 참조한다 — 신규 회원 생성 시 isDefault=true인
 * 행을 자동으로 찾아 배정한다. isDefault=true 행 "최대 1개"는 isDefaultKey 생성 컬럼 + UNIQUE로
 * DB가 강제한다. "최소 1개"는 DB가 못 막는 조건이라 DefaultMemberGradeInitializer(기동 시 확인
 * 후 없으면 시드)가 담당한다.
 *
 * 생성은 @Builder(access=PRIVATE) + 이름 있는 정적 팩토리(register())로만 열어둔다 — public
 * builder()를 그대로 노출하면 필수값(name) 누락을 컴파일 타임에 못 막는다.
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로 member_grade_id다 —
 * BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로 매핑하므로, @AttributeOverride로
 * 실제 DDL의 PK 컬럼명에 맞춰준다.
 */
@Entity
@Getter
@Table(name = "member_grade")
@AttributeOverride(name = "id", column = @Column(name = "member_grade_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberGrade extends BaseMutableTimeEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "promotion_rule", length = 255)
    private String promotionRule;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    // 필드 타입은 반드시 Byte여야 한다 — Hibernate는 스키마 validate 시 columnDefinition 문자열이
    // 아니라 "Java 타입 -> 기본 JDBC 타입" 매핑으로 기대 타입을 계산한다. Integer는 INTEGER(32bit)로
    // 매핑되어 실제 컬럼(TINYINT, 8bit)과 타입 category가 달라 validate에서 걸린다(Byte -> TINYINT가
    // 정확히 일치). Address.isDefaultKey는 DDL이 BIGINT라서 Long으로 맞물려 있어 이 문제가 없었다.
    @Column(name = "is_default_key", insertable = false, updatable = false, unique = true,
            columnDefinition = "TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END)")
    private Byte isDefaultKey;

    @Builder(access = AccessLevel.PRIVATE)
    private MemberGrade(String name, String promotionRule, boolean isDefault) {
        this.name = Objects.requireNonNull(name, "name");
        this.promotionRule = promotionRule;
        this.isDefault = isDefault;
    }

    /** 등급 정의 — 유일한 생성 진입점. */
    public static MemberGrade register(String name, String promotionRule, boolean isDefault) {
        return MemberGrade.builder()
                .name(name)
                .promotionRule(promotionRule)
                .isDefault(isDefault)
                .build();
    }

    @Override
    public String toString() {
        return "MemberGrade{id=%s, name=%s, isDefault=%s}".formatted(getId(), name, isDefault);
    }
}
