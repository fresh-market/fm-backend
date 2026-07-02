package com.example.freshmarket.member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Table(
    name = "member",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_member_email", columnNames = "email"),
        @UniqueConstraint(name = "uk_member_nickname", columnNames = "nickname")
    }
)
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_id")
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Column(name = "phone", nullable = false, length = 20)
    private String phone;

    // 등급은 연관관계 매핑 대신 FK 컬럼을 그대로 필드로 반영한다 (jpa-rdb-guideline 2장)
    @Column(name = "grade_id", nullable = false)
    private Long gradeId;

    @Column(name = "marketing_agreed", nullable = false)
    private boolean marketingAgreed;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MemberStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Member() {
        // JPA가 프록시, 리플렉션으로 사용하는 기본 생성자. 외부에서 직접 호출하지 않는다.
    }

    private Member(String email, String passwordHash, String name, String nickname,
                   String phone, Long gradeId, boolean marketingAgreed) {
        this.email = requireNonBlank(email, "email");
        this.passwordHash = requireNonBlank(passwordHash, "passwordHash");
        this.name = requireNonBlank(name, "name");
        this.nickname = requireNonBlank(nickname, "nickname");
        this.phone = requireNonBlank(phone, "phone");
        this.gradeId = Objects.requireNonNull(gradeId, "gradeId는 null일 수 없습니다.");
        this.marketingAgreed = marketingAgreed;
        this.status = MemberStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    // 이름 있는 정적 팩터리로 "회원가입"이라는 의도를 드러낸다 (effective-java 아이템 1)
    public static Member register(String email, String passwordHash, String name, String nickname,
                                  String phone, Long gradeId, boolean marketingAgreed) {
        return new Member(email, passwordHash, name, nickname, phone, gradeId, marketingAgreed);
    }

    // Setter 대신 상태 전이 의도를 드러내는 도메인 메서드로 노출한다 (F3 회원 탈퇴, 소프트딜리트)
    public void withdraw() {
        this.status = MemberStatus.WITHDRAWN;
        this.deletedAt = LocalDateTime.now();
    }

    public void block() {
        ensureNotWithdrawn();
        this.status = MemberStatus.BLOCKED;
    }

    public void reactivate() {
        ensureNotWithdrawn();
        this.status = MemberStatus.ACTIVE;
    }

    public void changeNickname(String nickname) {
        ensureNotWithdrawn();
        this.nickname = requireNonBlank(nickname, "nickname");
    }

    public void changePhone(String phone) {
        ensureNotWithdrawn();
        this.phone = requireNonBlank(phone, "phone");
    }

    public void changeGrade(Long gradeId) {
        ensureNotWithdrawn();
        this.gradeId = Objects.requireNonNull(gradeId, "gradeId는 null일 수 없습니다.");
    }

    // 탈퇴(소프트딜리트)는 종단 상태이므로, 탈퇴 후에는 다른 상태 전이를 막는다 (F3)
    private void ensureNotWithdrawn() {
        if (this.status == MemberStatus.WITHDRAWN) {
            throw new IllegalStateException("탈퇴한 회원의 상태는 변경할 수 없습니다.");
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "는 비어 있을 수 없습니다.");
        }
        return value;
    }

    public boolean isWithdrawn() {
        return this.status == MemberStatus.WITHDRAWN;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public String getNickname() {
        return nickname;
    }

    public String getPhone() {
        return phone;
    }

    public Long getGradeId() {
        return gradeId;
    }

    public boolean isMarketingAgreed() {
        return marketingAgreed;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true; // 같은 인스턴스면 비교할 것도 없이 true
        if (!(o instanceof Member)) return false;
        Member member = (Member) o;
        // id가 없는(영속화 전) 엔티티는 동일성을 판단할 기준이 없으므로 항상 다른 것으로 취급한다.
        return id != null && id.equals(member.id);
    }

    // id는 영속화 시점에 null -> 생성된 PK로 바뀐다. hashCode를 id 기반으로 두면
    // 저장 전 Set/Map에 넣은 엔티티가 저장 후 해시가 바뀌어 버킷을 잃어버린다.
    // 그래서 절대 바뀌지 않는 클래스 기준 고정값을 쓴다(분산이 나빠지는 대신 버킷 유실을 막는다).
    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        // passwordHash는 민감정보이므로 toString에 넣지 않는다 (로그 유출 방지)
        return "Member{" +
                "id=" + id +
                ", email='" + email + '\'' +
                ", nickname='" + nickname + '\'' +
                ", status=" + status +
                '}';
    }
}
