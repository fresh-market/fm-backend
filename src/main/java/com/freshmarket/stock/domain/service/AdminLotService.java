package com.freshmarket.stock.domain.service;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.ProductApi;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotExpireResponse;
import com.freshmarket.stock.domain.dto.AdminLotListResponse;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
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
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 관리자 화면에서 로트를 입고 등록하고, 조회하고, 만료 처리하는 기능을 담당한다
@Service
@Transactional(readOnly = true)
public class AdminLotService {

    private final StockLotRepository stockLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductApi productApi;
    private final ApplicationEventPublisher eventPublisher;

    public AdminLotService(StockLotRepository stockLotRepository,
            StockMovementRepository stockMovementRepository, ProductApi productApi,
            ApplicationEventPublisher eventPublisher) {
        this.stockLotRepository = stockLotRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.productApi = productApi;
        this.eventPublisher = eventPublisher;
    }

    /*
     * 로트를 입고하고 INBOUND 변동 이력을 함께 남긴다.
     * 같은 requestId로 재시도가 오면(API-5-07, AIP-155) 새로 입고하지 않고 최초 결과를 그대로 돌려준다.
     * requestId는 optionId와 함께 스코프한다 — 클라이언트가 같은 requestId를 다른 옵션에 잘못
     * 재사용해도 엉뚱한 옵션의 로트를 재시도 응답으로 돌려주지 않는다.
     * 먼저 조회해 일반적인(순차적인) 재시도를 검증 전에 걸러내고, save() 시점의 uk_lot_request_id
     * 위반은 두 요청이 거의 동시에 들어온 경합 상황을 잡는 안전망이다.
     */
    @Transactional
    public AdminLotResponse register(Long productId, Long optionId, AdminLotCreateRequest request) {
        Optional<StockLot> existingLot = stockLotRepository.findByRequestIdAndProductOptionId(
                request.requestId(), optionId);
        if (existingLot.isPresent()) {
            return AdminLotResponse.of(existingLot.get());
        }

        validateOptionExists(productId, optionId);
        LocalDate receivedDate = resolveReceivedDate(request.receivedDate());
        validateExpiryDate(receivedDate, request.expiryDate());

        StockLot stockLot = StockLot.register(request.requestId(), optionId, receivedDate, request.expiryDate(),
                request.initialQty());
        try {
            stockLotRepository.save(stockLot);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_lot_request_id")) {
                return AdminLotResponse.of(findByRequestIdOrThrow(request.requestId(), optionId));
            }
            /*
             * validateOptionExists()로 이미 확인했지만, 그 직후 옵션이 삭제되는 경합이면 fk_lot_option
             * 위반이 날 수 있다. 지금은 옵션 삭제 기능이 없어 실제로 발생하진 않지만, AdminProductService의
             * 카테고리/공급처 처리와 같은 방식으로 방어해 둔다.
             */
            if (isConstraintViolation(e, "fk_lot_option")) {
                throw new StockException(StockErrorCode.OPTION_NOT_FOUND, e);
            }
            /*
             * 알려진 제약(uk_lot_request_id, fk_lot_option) 위반이 아니면 원인을 알 수 없는 실패다.
             * Spring의 DataIntegrityViolationException을 그대로 던지면 저장소 계층의 예외 타입이 서비스
             * 경계 밖으로 새어나가므로, 원인은 유지한 채(cause) 더 명확한 메시지로 감싸서 던진다.
             */
            throw new IllegalStateException(
                    "로트 저장 중 알 수 없는 제약 위반이 발생했다: " + e.getMostSpecificCause().getMessage(), e);
        } catch (PessimisticLockingFailureException e) {
            return responseOfInProgressRetry(request.requestId(), optionId, e);
        }

        StockMovement movement = StockMovement.inbound(stockLot.getId(), request.initialQty());
        stockMovementRepository.save(movement);

        // 입고는 항상 가용 수량을 늘리기만 하므로, 별도 조회 없이도 이 옵션은 이제 품절이 아니라고 확정할 수 있다
        eventPublisher.publishEvent(new OptionAvailabilityChangedEvent(optionId, false));

        return AdminLotResponse.of(stockLot);
    }

    /*
     * 소비기한이 지난 AVAILABLE 로트를 찾아 EXPIRED로 전환하고 EXPIRE 이력을 남긴다(stock.md "만료
     * 로트 처리"). 하루 한 번 배치로 돌거나 관리자가 수동 호출한다.
     *
     * 품절 이벤트는 로트 하나하나가 아니라, 이번 호출로 영향받은 옵션 단위로 한 번씩만 발행한다.
     * 루프 안에서 로트를 만료시킬 때마다 바로 확인하면, 같은 옵션의 로트 여러 개가 한 번에 만료될 때
     * 마지막 로트가 아직 처리되지 않은 시점에도 "가용 로트 없음"으로 잘못 판단해 이벤트가 여러 번
     * 발행될 수 있다. 전부 만료시킨 뒤 옵션별로 한 번만 확인해야 이 문제가 없다.
     */
    @Transactional
    public AdminLotExpireResponse expireLots() {
        List<StockLot> targets = findExpiredTargetsForUpdate();

        List<StockLot> expiredLots = new ArrayList<>();
        for (StockLot lot : targets) {
            int beforeQty = lot.getAvailableQty();
            if (!lot.expire()) {
                continue;
            }
            /*
             * 예약으로 이미 다 소진된(availableQty=0) 로트도 status는 AVAILABLE로 남아있어(SOLD_OUT
             * 전환이 아직 없다) 이 조회에 걸릴 수 있다. 그런 로트는 줄어들 수량이 없으므로 EXPIRE
             * 이력을 남기지 않는다 — StockMovement가 quantity>0을 강제해서, 0을 넘기면 이 배치
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

        return AdminLotExpireResponse.of(expiredLots);
    }

    /*
     * 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로 감싼다.
     * 조회 자체가 쓰기 락이라(StockLotRepository.findByStatusAndExpiryDateBefore 참고) 같은 로트를
     * 건드리는 reserve()와 경합하면 여기서 실패할 수 있다 — 그 사이 beforeQty가 낡은 값이 되는 걸
     * 막기 위한 것이라, 실패하면 이 배치 전체를 롤백하고 재시도를 안내한다.
     */
    private List<StockLot> findExpiredTargetsForUpdate() {
        try {
            return stockLotRepository.findByStatusAndExpiryDateBefore(LotStatus.AVAILABLE, LocalDate.now());
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.EXPIRE_IN_PROGRESS, e);
        }
    }

    /*
     * save() 시점에 uk_lot_request_id 위반이 났다는 건 이 requestId를 가진 로트가 DB에 이미 있다는
     * 뜻이다. 같은 (requestId, optionId) 조합으로 재조회했는데도 없다면, 그 로트는 다른 optionId
     * 소속이라는 뜻 — 클라이언트가 같은 requestId를 서로 다른 옵션에 재사용한 것이다. 잘못된 옵션의
     * 로트를 성공 응답으로 돌려주는 대신 명확한 충돌 오류로 알려준다.
     */
    private StockLot findByRequestIdOrThrow(String requestId, Long optionId) {
        return stockLotRepository.findByRequestIdAndProductOptionId(requestId, optionId)
                .orElseThrow(() -> new StockException(StockErrorCode.REQUEST_ID_ALREADY_USED));
    }

    /*
     * save()가 유니크 위반이 아니라 락 대기 타임아웃(PessimisticLockingFailureException)으로 실패한 경우다.
     * 동시 재시도가 아직 커밋 전이라 유니크 위반조차 나지 않고 대기하다 시간 초과된 상황이므로, 그 사이
     * 커밋됐을 수도 있어 한 번 더 조회하고, 그래도 없으면 아직 처리 중이라는 뜻이라 클라이언트에게
     * 재시도를 안내한다(자체 재시도 루프는 넣지 않는다 — flush 실패 후 계속 쓰면 영속성 컨텍스트가
     * 불안정해질 수 있어, 일어나지도 않을 경합을 막으려 위험을 감수할 이유가 없다).
     */
    private AdminLotResponse responseOfInProgressRetry(String requestId, Long optionId,
            PessimisticLockingFailureException cause) {
        return stockLotRepository.findByRequestIdAndProductOptionId(requestId, optionId)
                .map(AdminLotResponse::of)
                .orElseThrow(() -> new StockException(StockErrorCode.REGISTRATION_IN_PROGRESS, cause));
    }

    /*
     * 상품의 로트 전체를 소비기한 오름차순(FEFO)으로 조회한다.
     * productId 존재 여부는 옵션 ID 목록이 비어있는지로 판정한다 — 상품 등록 시 옵션이 최소 1개
     * 필수이고(AdminProductCreateRequest.options가 @NotEmpty) 옵션 삭제 기능이 아직 없어서,
     * 지금은 "상품이 있으면 옵션도 항상 1개 이상 있다"는 불변식이 성립한다. 옵션 삭제 기능이
     * 생기면 이 판정도 다시 봐야 한다(그때는 상품 자체의 존재 여부를 별도로 확인해야 함).
     *
     * 옵션 목록 조회와 로트 조회가 별개의 쿼리 두 번이라, 격리수준을 REPEATABLE_READ로 명시해서
     * 두 쿼리가 같은 트랜잭션 안에서 일관된 스냅샷을 보게 강제한다 — DB 기본값에 기대지 않는다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLotListResponse findAllByProduct(Long productId, boolean availableOnly) {
        List<Long> optionIds = productApi.findOptionIds(productId);
        if (optionIds.isEmpty()) {
            throw new StockException(StockErrorCode.OPTION_NOT_FOUND);
        }

        List<StockLot> lots = availableOnly
                ? stockLotRepository.findByProductOptionIdInAndStatusOrderByExpiryDateAsc(optionIds,
                        LotStatus.AVAILABLE)
                : stockLotRepository.findByProductOptionIdInOrderByExpiryDateAsc(optionIds);

        return AdminLotListResponse.of(lots);
    }

    // optionId가 productId 소속으로 실제 존재하는지 확인한다
    private void validateOptionExists(Long productId, Long optionId) {
        if (!productApi.existsOption(productId, optionId)) {
            throw new StockException(StockErrorCode.OPTION_NOT_FOUND);
        }
    }

    // 생략된 입고일은 오늘로 채운다 (stock.md: "기본 오늘")
    private LocalDate resolveReceivedDate(LocalDate receivedDate) {
        return receivedDate != null ? receivedDate : LocalDate.now();
    }

    // 소비기한이 입고일보다 이르면 STOCK-001
    private void validateExpiryDate(LocalDate receivedDate, LocalDate expiryDate) {
        if (expiryDate.isBefore(receivedDate)) {
            throw new StockException(StockErrorCode.EXPIRY_BEFORE_RECEIVED);
        }
    }
}
