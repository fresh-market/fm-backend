package com.freshmarket.product.internal.repository;

import com.freshmarket.product.internal.entity.OptionAvailabilitySyncFailure;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptionAvailabilitySyncFailureRepository extends JpaRepository<OptionAvailabilitySyncFailure, Long> {

    Optional<OptionAvailabilitySyncFailure> findByProductOptionId(Long productOptionId);

    /*
     * (PERF-4-03) id 기준 keyset 페이지네이션. offset 페이지네이션과 달리, 배치 처리 중 앞쪽 행이
     * 삭제(성공 처리)돼도 뒤 페이지가 밀리거나 건너뛰는 문제가 없다 — "id > 마지막으로 본 id"
     * 조건이라 이미 지나온 위치보다 앞쪽에서 벌어지는 변화에 영향받지 않는다.
     *
     * (REL-2-07) attemptCount가 재시도 한도 이상인 행은 애초에 조회 대상에서 뺀다. 그대로 두면
     * 스케줄러가 매 주기 이미 포기한 행을 계속 다시 시도해 ERROR 로그만 무한히 반복된다 — 행
     * 자체는 사람이 볼 수 있게 지우지 않고 남기되(OptionAvailabilitySyncOutcomeService 참고),
     * 자동 재시도 대상에서만 뺀다.
     */
    List<OptionAvailabilitySyncFailure> findByIdGreaterThanAndAttemptCountLessThanOrderByIdAsc(
            Long id, int attemptCount, Pageable pageable);
}
