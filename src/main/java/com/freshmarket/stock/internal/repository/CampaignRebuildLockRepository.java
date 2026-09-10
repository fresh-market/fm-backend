package com.freshmarket.stock.internal.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 캠페인 대상 확정을 한 번에 하나만 돌게 하는 잠금.
 *
 * <p><b>계산 앞에서 잠근다.</b> 확정 절차가 "그날 행 삭제 → 후보 계산 → 삽입" 이라, 잠그지 않으면
 * 두 갈래가 각자 구간(갭) 락을 쥔 채 같은 구간에 넣으려다 교착이 난다. 갭 락끼리는 충돌하지 않아
 * 삭제 단계에서는 둘 다 통과하기 때문이다. 항상 있는 행 하나를 먼저 잠그면 자원이 하나라
 * 기다림이 원을 그릴 수가 없다. 덤으로 진 쪽이 후보 조회와 정렬을 헛돌리지 않는다.
 *
 * <p><b>기다리지 않고 즉시 판정한다(NOWAIT).</b> 관리자 재실행은 사람이 버튼을 누르고 응답을
 * 기다리는 경로다. 그냥 FOR UPDATE 로 두면 앞 회차가 끝날 때까지 매달렸다가 최악의 경우
 * innodb_lock_wait_timeout(기본 50초)을 다 채우고 실패한다. NOWAIT 는 그 자리에서 실패하므로
 * 호출부가 "지금 돌고 있다"(409) 로 바꿔 알릴 수 있다.
 *
 * <p><b>푸는 코드가 없다.</b> 행 잠금이라 트랜잭션이 끝나면 커밋이든 롤백이든 저절로 풀린다.
 * 명시적으로 잡고 명시적으로 놓는 방식(GET_LOCK, 애플리케이션 플래그)이 흔히 겪는, 예외 경로에서
 * 안 풀려 영영 막히는 문제가 여기서는 생기지 않는다. 대신 이 메서드는 반드시 트랜잭션 안에서
 * 불려야 한다 — 밖에서 부르면 잠금이 곧바로 풀려 아무것도 막지 못한다.
 */
@Repository
@RequiredArgsConstructor
public class CampaignRebuildLockRepository {

    // V34 가 심어 둔 유일한 행. 확정 대상이 언제나 "오늘" 하나뿐이라 잠금도 하나면 된다
    private static final String LOCK_NAME = "campaign_target_lot";

    private final EntityManager entityManager;

    /*
     * 잠금 행을 잡는다. 다른 트랜잭션이 쥐고 있으면 기다리지 않고 예외로 실패한다.
     *
     * 네이티브 쿼리를 쓰는 이유는 NOWAIT 를 확실히 내보내기 위해서다. JPA 의 잠금 시간 힌트는
     * 방언이 어떻게 옮기느냐에 달려 있어, 조용히 그냥 FOR UPDATE 로 나가면 기다리는 동작으로
     * 바뀐 것을 알아채기 어렵다.
     *
     * 예외를 여기서 삼키지 않는다. 잠금 경합과 그 밖의 DB 실패(표가 없다든지)를 boolean 하나로
     * 뭉개면, 스키마가 깨진 상황이 "다른 데서 돌고 있음" 으로 보고된다. 구분은 예외 종류를 보는
     * 호출부가 한다.
     */
    public void lockForRebuild() {
        entityManager.createNativeQuery(
                        "select lock_name from campaign_rebuild_lock where lock_name = :lockName for update nowait")
                .setParameter("lockName", LOCK_NAME)
                .getSingleResult();
    }
}
