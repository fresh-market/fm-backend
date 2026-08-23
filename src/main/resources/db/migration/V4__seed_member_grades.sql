-- (2026-08-18 16:40) docs/api/member.md: "초기 등급은 브론즈, 실버, 골드다. 선착순 쿠폰 캠페인의
-- 대상 등급과 연동된다." V1의 member_grade.name 컬럼 주석에도 이미 "등급명(브론즈/실버/골드)"라고
-- 적혀 있었다 — 스키마가 처음부터 이 세 이름을 전제하고 있었는데 애플리케이션 쪽
-- (DefaultMemberGradeInitializer)이 "일반" 하나만 시드하고 있어서 문서/스키마 의도와 어긋나 있었다.
-- 초기 데이터는 스키마와 함께 Flyway가 소유한다는 컨벤션(V1 참고)에 맞춰 여기서 넣는다.
-- 브론즈를 기본(is_default=TRUE) 등급으로 둔다 — 신규 회원 가입 시 자동 배정되는 등급이다.
INSERT INTO member_grade (name, promotion_rule, is_default, created_at, updated_at) VALUES
    ('브론즈', NULL, TRUE, NOW(6), NOW(6)),
    ('실버', NULL, FALSE, NOW(6), NOW(6)),
    ('골드', NULL, FALSE, NOW(6), NOW(6));
