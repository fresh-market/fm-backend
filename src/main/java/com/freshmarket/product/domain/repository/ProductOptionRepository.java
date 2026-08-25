package com.freshmarket.product.domain.repository;

import com.freshmarket.product.domain.entity.ProductOption;
import com.freshmarket.product.domain.entity.SaleStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    // 회원 조회용. OFF_SALE(판매안함) 옵션은 제외한다. 품절은 표시만 하고 노출은 유지한다
    List<ProductOption> findByProductIdAndSaleStatusNot(Long productId, SaleStatus saleStatus);

    // 상품 하나에 속한 옵션 전체를 찾는다. 재시도 응답을 재구성할 때 쓰인다
    List<ProductOption> findAllByProductId(Long productId);

    // 옵션이 존재하기만 하는지가 아니라 그 productId 소속인지까지 확인한다. ProductApi가 이걸 그대로 노출한다
    boolean existsByIdAndProductId(Long id, Long productId);

    /*
     * (DI-2-01) occurredAt이 마지막으로 반영된 사실보다 최신일 때만 갱신한다 — 동시에 도착하거나
     * 순서가 뒤바뀐 옵션 가용성 이벤트가 더 오래된 값으로 최신 값을 덮어쓰는 것을 막는다.
     * sold_out_synced_at이 NULL이면(아직 이벤트로 갱신된 적 없음) 무조건 반영한다.
     * 대상이 없거나 이미 더 최신 값이 반영돼 있으면 0을 반환하며, 둘 다 호출부에서는 정상 케이스다.
     */
    @Modifying
    @Query("update ProductOption o set o.soldOut = :soldOut, o.soldOutSyncedAt = :occurredAt "
            + "where o.id = :productOptionId "
            + "and (o.soldOutSyncedAt is null or o.soldOutSyncedAt < :occurredAt)")
    int updateSoldOutIfNewer(Long productOptionId, boolean soldOut, LocalDateTime occurredAt);
}