package com.freshmarket.admin.domain.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * admin 테이블 (V1__init_schema.sql). 삭제는 하드 삭제가 아니라 비활성화다.
 * 이력 테이블 다섯이 admin_id 를 참조해 하드 삭제가 불가능하기 때문이다.
 * 그래서 DB 컬럼명은 'DELETED' 지만 이 클래스의 의미는 "비활성화" 다 (deactivate 메서드명 참고).
 *
 * BaseMutableTimeEntity 의 PK 필드는 이름이 그냥 id 라 기본 매핑 컬럼도 'id' 다.
 * 이 테이블의 실제 PK 컬럼명은 admin_id 라서 @AttributeOverride 로 다시 이어준다.
 * 안 하면 Hibernate 스키마 검증이 "admin 테이블에 id 컬럼이 없다" 며 기동을 막는다.
 */
@Entity
@Table(name = "admin")
@AttributeOverride(name = "id", column = @Column(name = "admin_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Admin extends BaseMutableTimeEntity {

    // 아래 세 상수는 컬럼 정의의 length 값과 반드시 같아야 한다. 검증과 컬럼이 따로 놀면
    // DB 는 막는데 애플리케이션은 통과시키는(또는 그 반대인) 경우가 생긴다.
    private static final int LOGIN_ID_MAX_LENGTH = 50;
    private static final int PASSWORD_HASH_MAX_LENGTH = 255;
    private static final int NAME_MAX_LENGTH = 50;

    @Column(name = "login_id", nullable = false, length = LOGIN_ID_MAX_LENGTH)
    private String loginId;

    @Column(name = "password_hash", nullable = false, length = PASSWORD_HASH_MAX_LENGTH)
    private String passwordHash;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AdminStatus status;

    // 기기 한 대분만 저장한다. 로그인마다 덮어써 이전 리프레시 토큰은 자동으로 무효가 된다
    @Column(name = "refresh_token_hash", length = 64)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at")
    private LocalDateTime refreshTokenExpiresAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /*
     * 패키지 전용이다(private 가 아니다). 관리자 등록(계정 발급) 기능이 아직 없어 프로덕션
     * 호출부가 없다 — 있지도 않은 기능을 위해 public 팩터리를 미리 만들어두면 EC-3-08
     * (프로덕션 코드에 테스트 전용 생성 수단 금지) 위반이 된다.
     * 그래서 생성은 같은 패키지의 테스트 코드(AdminFixture, AdminTest)가 이 생성자를 직접 호출하는 것으로 대신한다.
     * 실제 등록 유스케이스가 생기면, 그 서비스가 아이디 중복 검사 등 발급 정책을 검사한 뒤 이 생성자를 감싸는 public 팩터리를 여기에 새로 추가한다.
     */
    Admin(String loginId, String passwordHash, String name, AdminRole role) {
        validateLoginId(loginId);
        validatePasswordHash(passwordHash);
        validateName(name);
        if (role == null) {
            throw new IllegalArgumentException("role 은 필수다");
        }
        this.loginId = loginId;
        this.passwordHash = passwordHash;
        this.name = name;
        this.role = role;
        this.status = AdminStatus.ACTIVE;   // 외부 입력을 받지 않는다 (EC R4)
    }

    private static void validateLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) {
            throw new IllegalArgumentException("loginId 는 필수다");
        }
        if (loginId.length() > LOGIN_ID_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "loginId 는 " + LOGIN_ID_MAX_LENGTH + "자를 넘을 수 없다: " + loginId.length());
        }
    }

    private static void validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("passwordHash 는 필수다");
        }
        if (passwordHash.length() > PASSWORD_HASH_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "passwordHash 는 " + PASSWORD_HASH_MAX_LENGTH + "자를 넘을 수 없다: " + passwordHash.length());
        }
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name 은 " + NAME_MAX_LENGTH + "자를 넘을 수 없다: " + name.length());
        }
    }

    public boolean isActive() { return this.status == AdminStatus.ACTIVE; }

    // 로그인 성공 시 리프레시 토큰을 갱신한다. 평문은 절대 받지 않는다 (호출부가 해시해서 넘긴다)
    public void issueRefreshToken(String refreshTokenHash, LocalDateTime expiresAt) {
        if (refreshTokenHash == null || refreshTokenHash.isBlank()) {
            throw new IllegalArgumentException("refreshTokenHash 는 필수다");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt 은 필수다");
        }
        this.refreshTokenHash = refreshTokenHash;
        this.refreshTokenExpiresAt = expiresAt;
    }

    /*
     * 비활성화한다. 본인 계정 비활성화 금지, 마지막 최고관리자 비활성화 금지 같은 정책은
     * "관리자 삭제(비활성화)" 기능의 서비스가 검사한다. 이 메서드는 그 기능이 아직 없는 지금도
     * 테스트 픽스처가 비활성 계정을 만들 수 있도록 최소 형태로 둔다.
     * DB 제약(chk_admin_deleted)이 요구하는 대로 리프레시 토큰도 함께 비운다.
     */
    public void deactivate(LocalDateTime deactivatedAt) {
        if (deactivatedAt == null) {
            throw new IllegalArgumentException("deactivatedAt 은 필수다");
        }
        if (this.status == AdminStatus.DELETED) {
            throw new IllegalStateException("이미 비활성화된 계정이다: " + this.loginId);
        }
        this.status = AdminStatus.DELETED;
        this.deletedAt = deactivatedAt;
        this.refreshTokenHash = null;
        this.refreshTokenExpiresAt = null;
    }
}