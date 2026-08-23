package com.freshmarket.member.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import com.freshmarket.common.logging.PiiMasker;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;

/**
 * 컬럼명/제약은 V1__init_schema.sql의 member 테이블을 그대로 따른다 — 스키마는 Flyway가
 * 소유하고(ddl-auto: validate), 이 엔티티는 그 구조에 맞춰 매핑만 한다.
 *
 * 생성은 @Builder(access=PRIVATE) + 이름 있는 정적 팩토리(register())로만 열어둔다 — public
 * builder()를 그대로 노출하면 필수값(provider/providerUserId/memberGradeId) 누락을 컴파일
 * 타임에 못 막는다.
 *
 * PK 컬럼명은 스키마 전체 컨벤션(schema-design-rationale.md)대로 member_id다 —
 * BaseMutableTimeEntity의 id 필드는 컬럼명을 "id"로 매핑하므로, @AttributeOverride로
 * 실제 DDL의 PK 컬럼명에 맞춰준다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "member")
@AttributeOverride(name = "id", column = @Column(name = "member_id"))
@Check(name = "chk_member_status", constraints = "status IN ('PENDING_PROFILE','ACTIVE','BLOCKED','WITHDRAWN')")
@Check(name = "chk_member_refresh_token", constraints = "(refresh_token_hash IS NULL AND refresh_token_expires_at IS NULL) "
        + "OR (refresh_token_hash IS NOT NULL AND refresh_token_expires_at IS NOT NULL)")
@Check(name = "chk_member_withdrawn", constraints = "(status = 'WITHDRAWN' AND deleted_at IS NOT NULL) "
        + "OR (status <> 'WITHDRAWN' AND deleted_at IS NULL)")
public class Member extends BaseMutableTimeEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 30)
    private SocialType provider;

    @Column(name = "provider_user_id", nullable = false, length = 100)
    private String providerUserId;

    /**
     * "{provider}:{providerUserId}" 활성 식별 키 — deleted_at IS NULL을 기준으로 DB가 계산하는
     * GENERATED 컬럼(VIRTUAL, DDL에 STORED 명시 없음). 애플리케이션은 직접 쓰지 않는다.
     */
    @Column(name = "active_provider_key", insertable = false, updatable = false, unique = true, length = 140,
            columnDefinition = "VARCHAR(140) GENERATED ALWAYS AS "
                    + "(CASE WHEN deleted_at IS NULL THEN CONCAT(provider, ':', provider_user_id) ELSE NULL END)")
    private String activeProviderKey;

    // 카카오에서 받지 않고 온보딩 폼 입력값을 저장한다(updateProfile() 참고).
    @Column(length = 255)
    private String email;

    // DDL: VARCHAR(50). 닉네임 유일성은 요구사항이 아니다 — 팀 결정으로 중복 방지 로직/제약을
    // 전부 뺐다(2026-08-19). 예전엔 existsByNickname() 선조회로 검사했는데 그 방식 자체가
    // 동시성 레이스에 취약했다(DI-3-01) — 그걸 DB UNIQUE로 막는 대신, 애초에 안 겹쳐야 할
    // 이유가 없다고 보고 요구사항을 없앴다.
    @Column(length = 50)
    private String nickname;

    // DDL의 member.name(폼 입력 실명) — 카카오 nickname과 별개 필드.
    @Column(length = 50)
    private String name;

    @Column(name = "member_grade_id", nullable = false)
    private Long memberGradeId;

    @Column(name = "is_marketing_agreed", nullable = false)
    private boolean marketingAgreed;

    @Column(length = 20)
    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "VARCHAR(30) COLLATE utf8mb4_0900_as_cs")
    private MemberStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 로그인/토큰 재발급 인프라만 이 두 컬럼을 직접 건드린다.
    @Column(name = "refresh_token_hash", columnDefinition = "CHAR(64)")
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Builder(access = AccessLevel.PRIVATE)
    private Member(SocialType provider, String providerUserId, Long memberGradeId) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.providerUserId = Objects.requireNonNull(providerUserId, "providerUserId");
        this.memberGradeId = Objects.requireNonNull(memberGradeId, "memberGradeId");
        this.status = MemberStatus.PENDING_PROFILE;
    }

    /** 카카오 최초 로그인 시 신규 회원 생성 — 유일한 생성 진입점. */
    public static Member register(SocialType provider, String providerUserId, Long memberGradeId) {
        return Member.builder()
                .provider(provider)
                .providerUserId(providerUserId)
                .memberGradeId(memberGradeId)
                .build();
    }

    // 회원 역할은 지금 ROLE_USER 하나뿐이다(관리자 역할 ADMIN/SUPER_ADMIN과 다른 축) — 행마다
    // 달라질 값이 아니라서 컬럼으로 두지 않고 상수로 돌려준다. JWT의 role 클레임과 Redis
    // 키("refreshToken:{role}:{id}")의 네임스페이스 구분에 이 값을 그대로 쓴다(MemberTokenService
    // 참고). 회원에게도 역할 구분이 실제로 필요해지면 그때 컬럼으로 승격한다.
    public MemberRole getRole() {
        return MemberRole.ROLE_USER;
    }

    /** DB의 GENERATED 컬럼 계산식과 반드시 같은 규칙을 유지해야 한다(MemberRepository 조회 조건용). */
    public static String buildActiveProviderKey(SocialType provider, String providerUserId) {
        return provider.name() + ":" + providerUserId;
    }

    public Member assignNickname(String nickname) {
        this.nickname = nickname;
        return this;
    }

    // (2026-08-18 13:25) docs/api/member.md 기준 PATCH /v1/members/me는 하나뿐이고 "보낸
    // 필드만 바뀐다"(부분 수정)고 명시한다 — 예전엔 온보딩(완료 시 ACTIVE 전환 + 약관동의 필수
    // 체크)과 일반 수정(PATCH /members/me)이 별도 엔드포인트/메서드였는데, 문서엔 온보딩용
    // 엔드포인트가 따로 없다. 사용자 확인 후 두 흐름을 이 메서드 하나로 합쳤다: name/nickname/email이
    // (기존값+새값 합쳐) 전부 채워지면 PENDING_PROFILE -> ACTIVE로 자동 전환한다. 약관 동의는
    // 별도로 추적하지 않고 marketingAgreed(마케팅 수신 동의)만 받는다(사용자 확인: "terms는
    // 없애기로 했다, 마케팅만 있으면 된다"). 회원 단건 주소 필드는 이 메서드에서 다루지 않는다 —
    // 배송지는 문서상 회원당 여러 건인 별도 리소스라 Address 엔티티/테이블이 전담하고, member
    // 테이블 자체에는 주소 컬럼이 없다.
    // 예전 온보딩 전용 흐름(completeOnboarding)은 죽은 코드라 이 파일에서는 지웠다 — 아직
    // 이걸 호출하는 MemberOnboardingService/MemberOnboardingServiceTest/MemberOnboardingRequest
    // 세 파일이 로컬에 남아 있다면 함께 지운다(아래 "정리 안내" 참고).
    public Member updateProfile(String name, String nickname, String email, String phone, Boolean marketingAgreed) {
        if (name != null) {
            this.name = name;
        }
        if (nickname != null) {
            assignNickname(nickname);
        }
        if (email != null) {
            this.email = email;
        }
        if (phone != null) {
            this.phone = phone.isBlank() ? null : phone;
        }
        if (marketingAgreed != null) {
            this.marketingAgreed = marketingAgreed;
        }
        if (this.status == MemberStatus.PENDING_PROFILE
                && this.name != null && !this.name.isBlank()
                && this.nickname != null && !this.nickname.isBlank()
                && this.email != null && !this.email.isBlank()) {
            this.status = MemberStatus.ACTIVE;
        }
        return this;
    }

    // (2026-08-18 17:52) 죽은 completeOnboarding()을 지웠다 — MemberOnboardingService로
    // 흡수된 지 오래고, 위 updateProfile()이 그 역할을 대신한다. MemberOnboardingService/
    // MemberOnboardingServiceTest/MemberOnboardingRequest는 이 메서드가 없으면 컴파일이
    // 깨지므로 반드시 같이 지운다 — 아래 "정리 안내" 참고(샌드박스는 파일 삭제를 못 해 로컬에서
    // 직접 지워야 한다).

    public boolean isPendingProfile() {
        return this.status == MemberStatus.PENDING_PROFILE;
    }

    public boolean isWithdrawn() {
        return this.status == MemberStatus.WITHDRAWN;
    }

    /**
     * 탈퇴 처리(소프트 삭제). TODO(주문 도메인 추가 시): "진행 중 주문/미완료 환불이 있으면 탈퇴 불가"
     * 체크가 필요하다 — order 모듈이 없는 지금은 체크하지 않는다.
     */
    public void withdraw() {
        if (isWithdrawn()) {
            return;
        }
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
    }

    /** email/phone/providerUserId 등 민감정보가 새어나가지 않도록 방어적으로 오버라이드. */
    @Override
    public String toString() {
        return "Member{id=%s, nickname=%s, email=%s, status=%s, role=%s}"
                .formatted(getId(), nickname, PiiMasker.maskEmail(email), status, getRole());
    }
}
