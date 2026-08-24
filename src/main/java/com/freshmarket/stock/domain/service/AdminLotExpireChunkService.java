package com.freshmarket.stock.domain.service;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.entity.StockMovement;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * (DI-4-03/PERF-4-03) AdminLotService.expireLots()가 만료 대상 전체를 한 트랜잭션·영속성
 * 컨텍스트에 적재하지 않도록, 청크 하나를 처리하는 트랜잭션 경계를 별도 빈으로 뺐다. 같은 클래스
 * 안에서 this.xxx()로 @Transactional 메서드를 불러봐야 프록시를 안 거쳐 트랜잭션이 조용히
 * 무시되므로(Spring AOP 자기 자신 호출 한계, KakaoUnlinkRetryOutcomeService와 같은 이유),
 * 반복 호출하는 쪽(AdminLotService)과 트랜잭션을 여는 쪽을 다른 빈으로 나눈다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class AdminLotExpireChunkService {

    private final StockLotRepository stockLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ApplicationEventPublisher eventPublisher;

    /*
     * 청크 하나(최대 chunkSize건)를 만료 처리한다.
     *
     * 품절 이벤트는 이 청크에서 실제로 영향받은 옵션 단위로만 발행한다 — 같은 옵션의 나머지 로트가
     * 아직 다른 청크에 남아있으면 그 로트는 이 시점에 여전히 AVAILABLE로 조회되므로 여기서 조기에
     * (잘못) 발행되지 않는다. 그 로트가 속한 청크가 처리될 때 다시 확인되어 그때 정확히 한 번
     * 발행된다 — 청크로 나눠도 옵션당 이벤트 발행 시점만 달라질 뿐 정확성은 그대로다.
     */
    public List<StockLot> expireChunk(int chunkSize) {
        List<StockLot> targets = findExpiredTargetsForUpdate(chunkSize);

        List<StockLot> expiredLots = new ArrayList<>();
        for (StockLot lot : targets) {
            int beforeQty = lot.getAvailableQty();
            if (!lot.expire()) {
                continue;
            }
            /*
             * 예약으로 이미 다 소진된(availableQty=0) 로트도 status는 AVAILABLE로 남아있어(SOLD_OUT
             * 전환이 아직 없다) 이 조회에 걸릴 수 있다. 그런 로트는 줄어들 수량이 없으므로 EXPIRE
             * 이력을 남기지 않는다 — StockMovement가 quantity>0을 강제해서, 0을 넘기면 이 청크
             * 전체가 예외로 롤백된다.
             */
            if (beforeQty > 0) {
                stockMovementRepository.save(StockMovement.expire(lot.getId(), beforeQty));
            }
            expiredLots.add(lot);
        }

        expiredLots.stream()
                .map(StockLot::getProductOptionId)
                .distinct()
                .filter(optionId -> !stockLotRepository.existsByProductOptionIdAndStatus(
                        optionId, LotStatus.AVAILABLE))
                .forEach(optionId -> eventPublisher.publishEvent(new OptionAvailabilityChangedEvent(optionId, true)));

        return expiredLots;
    }

    /*
     * 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로 감싼다.
     * 조회 자체가 쓰기 락이라(StockLotRepository.findByStatusAndExpiryDateBefore 참고) 같은 로트를
     * 건드리는 reserve()와 경합하면 여기서 실패할 수 있다 — 그 사이 beforeQty가 낡은 값이 되는 걸
     * 막기 위한 것이라, 실패하면 이 청크를 롤백하고 재시도를 안내한다.
     */
    private List<StockLot> findExpiredTargetsForUpdate(int chunkSize) {
        try {
            Pageable pageable = PageRequest.of(0, chunkSize);
            return stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now(), pageable);
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.EXPIRE_IN_PROGRESS, e);
        }
    }
}
