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
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
        this.nickname = nickname;
        this.phone = phone;
        this.gradeId = gradeId;
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
        this.status = MemberStatus.BLOCKED;
    }

    public void reactivate() {
        this.status = MemberStatus.ACTIVE;
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname;
    }

    public void changePhone(String phone) {
        this.phone = phone;
    }

    public void changeGrade(Long gradeId) {
        this.gradeId = gradeId;
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
        if (this == o) return true; // 성능을 위해 같은 인스턴스면 비교할 것도 없이 true
        if (!(o instanceof Member member)) return false;
        return Objects.equals(id, member.id);
    }

    // hashCode로 칸을 찾고, 그 칸 안에서 equals로 비교
    // 1. hashCode(): 버킷 10 안에는 회원 A(id=1)와 회원 B(id=2)가 들어 있음
    // 2. equals(): 찾으려는 값은 회원 B(id=2)이어서 칸 10의 회원 B(id=2)와 일치
    @Override
    public int hashCode() {
        return Objects.hash(id);
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
