package com.freshmarket.coupon.domain;

import com.freshmarket.coupon.domain.redis.CouponSeqAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * 이 리스너가 이벤트를 연 트랜잭션이 커밋된 뒤에 순번 확보 스크립트를 Redis 서버에 올려 둔다.
 *
 * 이 왕복을 트랜잭션 밖으로 뺀 이유는 안에 있을 까닭이 없어서다. 스크립트 내용이 고정이라
 * 언제 올리든 결과가 같고 이 호출은 실패해도 삼키므로, 여는 트랜잭션이 coupon 행을 잠근 채
 * Redis 를 기다릴 이유가 없다. 준비 단계(CouponSeqInitializer.prepare)는 반대로 순서 요구가
 * 있어 트랜잭션 안에 남는다. 그것이 먼저 커밋돼야 경합하는 open 이 도는 이벤트의 카운터를
 * 못 지운다.
 *
 * 커밋 뒤로 밀려서 첫 요청이 스크립트보다 먼저 도착할 틈이 아주 짧게 생긴다. 그때는 요청 스레드가
 * EVALSHA 로 튕긴 뒤 EVAL 로 스스로 올리므로 발급이 막히지 않는다.
 *
 * 리스너를 domain.service 에 두지 않는 것은 그 패키지가 메서드 커버리지 100% 대상이기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class CouponEventOpenedListener {

    private final CouponSeqAllocator allocator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(CouponEventOpenedEvent event) {
        allocator.preloadScript();
    }
}
