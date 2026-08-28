package com.freshmarket.coupon.domain.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.freshmarket.coupon.domain.entity.MemberCoupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급분의 상태를 옮긴다. 전이가 전부 조건부 갱신인 것이 이 인터페이스의 전부다.
 *
 * <p>스키마가 R1~R4 는 막지만 <b>R5(같은 전이가 반복되거나 겹쳐도 한 번만 반영)는 못 막는다.</b>
 * 같은 전이를 두 번 요청하는 것은 스키마가 보기에 정상적인 UPDATE 두 번이다
 * ({@code docs/coupon/coupon.md} 2장).
 *
 * <p>그래서 <b>읽고 판단한 뒤 쓰지 않는다.</b> 지금 상태를 {@code WHERE} 에 넣어 한 문장으로 만든다.
 *
 * <pre>
 * 반복 요청   두 번째부터 status 가 조건에 안 맞아 0행
 * 동시 요청   행 락으로 줄을 서고, 뒤엣것은 깨어났을 때 조건이 어긋나 역시 0행
 * </pre>
 *
 * <p>이력을 엔티티로 두지 않고 네이티브로 넣는다. 만료 배치가 청크 단위로 한 번에 넣어야 해서
 * 어차피 집합 연산이 필요하고, 두 경로가 같은 모양이어야 이력이 갈리지 않는다.
 */
public interface MemberCouponRepository extends JpaRepository<MemberCoupon, Long> {

    /**
     * 발급분을 사용 처리한다. 지금 {@code ISSUED} 일 때만 바뀐다.
     *
     * <p>{@code memberId} 를 조건에 넣는 것이 소유권 검증이다. 남의 발급분 번호를 실어 보내도
     * 조건이 안 맞아 0행이 되고, 그 0행은 "없음" 과 구분되지 않는다.
     *
     * @return 1 이면 이번 요청이 바꿨다. 0 이면 이미 바뀌었거나 쓸 수 없는 상태이거나 없다
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE member_coupon
               SET status = 'USED', used_at = :now, updated_at = :now
             WHERE member_coupon_id = :memberCouponId AND member_id = :memberId AND status = 'ISSUED'
            """, nativeQuery = true)
    int markUsed(@Param("memberCouponId") long memberCouponId,
                 @Param("memberId") long memberId,
                 @Param("now") LocalDateTime now);

    /**
     * 주문이 취소되어 사용을 철회한다. 지금 {@code USED} 일 때만 바뀐다.
     *
     * <p>{@code used_at} 을 함께 비운다. {@code chk_mc_used_at} 이 사용 상태와 사용 시각을 묶고
     * 있어, 안 비우면 이 갱신 자체가 제약에 걸려 실패한다.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE member_coupon
               SET status = 'CANCELED', used_at = NULL, updated_at = :now
             WHERE member_coupon_id = :memberCouponId AND member_id = :memberId AND status = 'USED'
            """, nativeQuery = true)
    int markCanceled(@Param("memberCouponId") long memberCouponId,
                     @Param("memberId") long memberId,
                     @Param("now") LocalDateTime now);

    /**
     * 만료 배치가 한 번에 볼 대상을 고른다. 청크로 끊는 것은 대량 갱신이 락을 길게 잡지 않게 하려는 것이다.
     *
     * <p>유효기간은 {@code coupon} 이 갖는다. {@code V30} 이 발급 시점 복사본을 걷어내고
     * 값을 {@code coupon} 한 곳에 두었기 때문에 발급 행만 봐서는 만료 여부를 알 수 없다.
     */
    @Query(value = """
            SELECT mc.member_coupon_id
              FROM member_coupon mc
              JOIN coupon c ON c.coupon_id = mc.coupon_id
             WHERE mc.status = 'ISSUED' AND c.valid_to < :today
             LIMIT :chunk
            """, nativeQuery = true)
    List<Long> findExpirable(@Param("today") LocalDate today, @Param("chunk") int chunk);

    /**
     * 유효기간이 지난 발급분을 만료 처리한다.
     *
     * <p>조건을 그대로 다시 거는 것이 중요하다. 대상을 고른 뒤 갱신하기까지 사이에 사용 요청이
     * 끼어들 수 있고, <b>그때는 사용 쪽이 이기고 이 갱신이 그 행을 건너뛰어야 한다.</b>
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = """
            UPDATE member_coupon mc
              JOIN coupon c ON c.coupon_id = mc.coupon_id
               SET mc.status = 'EXPIRED', mc.updated_at = :now
             WHERE mc.member_coupon_id IN (:ids) AND mc.status = 'ISSUED' AND c.valid_to < :today
            """, nativeQuery = true)
    int markExpired(@Param("ids") List<Long> ids,
                    @Param("today") LocalDate today,
                    @Param("now") LocalDateTime now);

    /**
     * 전이 하나를 이력에 남긴다. 상태를 바꾼 그 트랜잭션 안에서 부른다.
     *
     * <p>나누면 상태는 한 번 바뀌었는데 이력이 두 줄이 되거나 그 반대가 된다.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO member_coupon_status_history
                   (member_coupon_id, from_status, to_status, reason, created_at)
            VALUES (:memberCouponId, :fromStatus, :toStatus, :reason, :now)
            """, nativeQuery = true)
    void recordTransition(@Param("memberCouponId") long memberCouponId,
                          @Param("fromStatus") String fromStatus,
                          @Param("toStatus") String toStatus,
                          @Param("reason") String reason,
                          @Param("now") LocalDateTime now);

    /**
     * 방금 만료된 것들의 이력을 한 번에 남긴다.
     *
     * <p>{@code updated_at = :now} 로 되찾는 것이 요점이다. 우리가 넘긴 시각을 갱신에 박아 두고
     * 그 값으로 고른다. 상태만 보고 고르면 <b>남이 같은 사이에 만료시킨 행까지 이력에 들어간다.</b>
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO member_coupon_status_history
                   (member_coupon_id, from_status, to_status, reason, created_at)
            SELECT member_coupon_id, 'ISSUED', 'EXPIRED', :reason, :now
              FROM member_coupon
             WHERE member_coupon_id IN (:ids) AND status = 'EXPIRED' AND updated_at = :now
            """, nativeQuery = true)
    void recordExpiredTransitions(@Param("ids") List<Long> ids,
                                  @Param("reason") String reason,
                                  @Param("now") LocalDateTime now);

    /** 0행의 사유를 가르려고 읽는다. 전이가 실패한 뒤에만 도는 경로다. */
    @Query(value = """
            SELECT status FROM member_coupon
             WHERE member_coupon_id = :memberCouponId AND member_id = :memberId
            """, nativeQuery = true)
    List<String> findStatus(@Param("memberCouponId") long memberCouponId, @Param("memberId") long memberId);
}
