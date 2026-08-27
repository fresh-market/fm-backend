package com.freshmarket.coupon.domain.repository;

import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.coupon.domain.entity.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 경로는 요청 스레드가 쿠폰 한 건을 PK 로 읽는 것이 전부라 기본 메서드로 족하다. 아래
 * 메서드들은 관리자 API 와 종료 배치가 쓴다.
 *
 * <p>상태를 바꾸는 것은 모두 네이티브 조건부 갱신이다. 읽고 바꾸고 저장하면 그 사이에 남이
 * 끼어들 수 있고, 관리자와 배치가 같은 행을 동시에 건드리는 자리라 그 틈을 두지 않는다.
 * 조건을 SQL 안에 두었으므로 <b>갱신된 행 수가 0 이면 그 조건이 거짓이었다는 뜻</b>이다.
 *
 * <p>시각은 앱이 넘긴다. {@code NOW()} 를 쓰면 <b>MySQL 서버의 시간대가 판정에 끼어든다.</b>
 * 앱은 {@code issue_end_at} 을 자기 기본 시간대의 벽시계로 쓰는데, DB 가 다른 시간대면 그
 * 둘을 견주는 순간 시차만큼 어긋난다. 배포 환경이 우연히 같은 시간대라 안 드러날 뿐이고,
 * 개발자 기계에서는 실제로 어긋난다.
 *
 * <p>이 선택은 순번 확보 스크립트와 반대다. 그쪽은 인스턴스 A 가 쓴 점수를 B 가 재므로 Redis
 * 시계를 써야 했고, 여기는 <b>같은 앱이 쓴 값을 같은 앱이 견주므로</b> 앱 시계가 맞다.
 */
public interface CouponRepository extends JpaRepository<Coupon, Long> {

    /**
     * 발급 스위치를 켠다. 이미 켜져 있으면 아무 행도 안 바꾼다.
     *
     * <p>이 갱신이 커밋될 때까지 다른 트랜잭션은 그 행에서 막히고 스위치도 못 본다. 그래서
     * 호출자가 커밋 전에 Redis 를 세워 두면 <b>남이 스위치를 보는 시점에는 카운터가 이미 서 있다.</b>
     *
     * @return 1 이면 이 호출이 켰다. 0 이면 남이 이미 켰다
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE coupon SET is_active = TRUE, updated_at = :now
             WHERE coupon_id = :couponId AND is_active = FALSE
            """, nativeQuery = true)
    int activateIfInactive(@Param("couponId") long couponId, @Param("now") LocalDateTime now);

    /**
     * 관리자가 이벤트를 끈다. 소진됐거나 마감 시각이 지났을 때만 꺼진다.
     *
     * <p>서비스가 같은 조건을 미리 보고 사유를 갈라 답한다. 여기 조건은 그 확인과 갱신 사이에
     * 남이 끼어드는 것을 막는 <b>경합 방어</b>다.
     *
     * @return 1 이면 이 호출이 껐다. 0 이면 이미 꺼졌거나 아직 끌 수 없는 상태다
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE coupon c SET c.is_active = FALSE, c.updated_at = :now
             WHERE c.coupon_id = :couponId AND c.is_active = TRUE
               AND ((c.issue_end_at IS NOT NULL AND c.issue_end_at <= :now)
                 OR (SELECT COUNT(*) FROM member_coupon mc WHERE mc.coupon_id = c.coupon_id) >= c.total_quantity)
            """, nativeQuery = true)
    int deactivateIfClosable(@Param("couponId") long couponId, @Param("now") LocalDateTime now);

    /**
     * 관리자가 발급 시각을 바꾼다. 아직 시작하지 않은 이벤트만 바뀐다.
     *
     * <p>시작한 이벤트의 일정을 관리자가 늘리거나 줄이지 못하게 한다. 끝 시각만 미루는 것도
     * 막는다. 사용자가 본 마감이 뒤로 밀리면 그것도 약속을 흔드는 것이다.
     *
     * @return 1 이면 바뀌었다. 0 이면 이미 시작해 잠겼다
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE coupon SET issue_start_at = :issueStartAt, issue_end_at = :issueEndAt, updated_at = :now
             WHERE coupon_id = :couponId
               AND (issue_start_at IS NULL OR issue_start_at > :now)
            """, nativeQuery = true)
    int updateIssuePeriodIfNotStarted(@Param("couponId") long couponId,
                                      @Param("issueStartAt") LocalDateTime issueStartAt,
                                      @Param("issueEndAt") LocalDateTime issueEndAt,
                                      @Param("now") LocalDateTime now);

    /**
     * 배치가 마감 시각이 지난 이벤트를 끈다. 관리자가 누르지 않아도 끝나게 하는 장치다.
     *
     * <p>배치는 소진으로 끝내지 않는다. 카운터가 한도를 넘어도 {@code free} 에 반납된 번호가
     * 남아 있으면 스크립트가 그것을 다시 내주므로 최종이 아니다. <b>배치가 보는 것은 마감 시각
     * 하나뿐이다.</b>
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE coupon SET is_active = FALSE, updated_at = :now
             WHERE is_active = TRUE AND total_quantity IS NOT NULL
               AND issue_end_at IS NOT NULL AND issue_end_at <= :now
            """, nativeQuery = true)
    int deactivateFinishedEvents(@Param("now") LocalDateTime now);

    /**
     * 배치가 정리할 쿠폰을 찾는다. Redis 에 키가 남아 있는지로 찾지 않는다.
     *
     * <p>키로 찾으면 TTL 이 키를 먼저 지웠을 때 배치가 대상을 놓쳐 발급 수 맞추기를 건너뛴다.
     * 배치가 DB 만 보면 TTL 길이와 배치 주기가 서로 묶이지 않는다.
     *
     * <p>하한 60초가 "진행 중인 배치가 결판날 때까지 기다린다" 를 만든다. 이것이 없으면 배치가
     * 방금 끈 쿠폰을 같은 실행에서 정리해 대기가 0 이 된다. Redis 를 마지막으로 만지는 것은
     * 요청 스레드의 순번 확보가 아니라 <b>플러시 스레드의 반납</b>이라, 그 커밋이나 실패까지
     * 끝나야 Redis 호출이 다 끝난다.
     *
     * <p>상한 7일이 비용을 이력 크기에서 떼어 낸다. 이것이 없으면 3년 전에 끝난 이벤트까지
     * 후보가 되고, <b>DB 가 그 쿠폰마다 {@code member_coupon} 을 다시 센다.</b> 한 번 맞춘
     * 쿠폰은 두 값이 같아져 결과에서 빠지지만, 빠졌다는 것을 알아내려고 DB 가 매번 센다.
     *
     * <p>상한을 넘겨 놓친 쿠폰은 {@code issued_quantity} 가 어긋난 채 남는다. 요청 스레드가 그
     * 값을 안 보므로 발급이 틀어지지 않고, Redis 키는 TTL 이 따로 치운다.
     */
    @Query(value = """
            SELECT coupon_id FROM coupon c
             WHERE is_active = FALSE AND total_quantity IS NOT NULL
               AND updated_at < :closedBefore
               AND updated_at > :closedAfter
               AND issued_quantity <> (SELECT COUNT(*) FROM member_coupon WHERE coupon_id = c.coupon_id)
            """, nativeQuery = true)
    List<Long> findCleanupTargets(@Param("closedBefore") LocalDateTime closedBefore,
                                  @Param("closedAfter") LocalDateTime closedAfter);

    /**
     * 배치가 발급 수를 실제 행 수로 맞춘다.
     *
     * <p>아무도 발급 중에 이 값을 갱신하지 않는다. 상한은 순번이 행 단위로 강제하므로 이 값이
     * 판정에 쓰이지 않는다. 요청 스레드가 발급마다 올리면 쿠폰 한 행에 락이 몰려 순번을 둔
     * 의미가 사라진다.
     *
     * <p>이 갱신은 {@code updated_at} 을 건드리지 않는다. 그 값이 "언제 껐나" 를 뜻하고 위
     * 대기 조건이 그것을 재기 때문이다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE coupon c
               SET issued_quantity = (SELECT COUNT(*) FROM member_coupon WHERE coupon_id = c.coupon_id)
             WHERE coupon_id = :couponId
            """, nativeQuery = true)
    int syncIssuedQuantity(@Param("couponId") long couponId);

    /**
     * 실제로 발급된 행 수를 센다. 관리자가 이벤트를 끌 수 있는지 판정할 때 쓴다.
     *
     * <p>행이 {@code total_quantity} 만큼 있으면 모든 번호가 행으로 실재하므로 스크립트가 회수할
     * 것이 없다. 플러시 스레드가 밀린 만큼 이 값이 늦게 차지만, <b>관리자가 늦게 끄는 쪽이
     * 안전하다.</b>
     */
    @Query(value = "SELECT COUNT(*) FROM member_coupon WHERE coupon_id = :couponId", nativeQuery = true)
    int countIssued(@Param("couponId") long couponId);
}
