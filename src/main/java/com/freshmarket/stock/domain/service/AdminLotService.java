package com.freshmarket.stock.domain.service;

import static com.freshmarket.common.exception.ConstraintViolations.isConstraintViolation;

import com.freshmarket.common.response.PageCursor;
import com.freshmarket.common.response.PageTokens;
import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.ProductApi;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotDisposeRequest;
import com.freshmarket.stock.domain.dto.AdminLotExpireResponse;
import com.freshmarket.stock.domain.dto.AdminLotListResponse;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
import com.freshmarket.stock.domain.entity.DisposalReason;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.entity.StockMovement;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockLotQueryRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

// 관리자 화면에서 로트를 입고 등록하고, 조회하고, 폐기·만료 처리하는 기능을 담당한다
@Slf4j
@Service
@Transactional(readOnly = true)
public class AdminLotService {

    // (DI-4-03/PERF-4-03) expireLots()가 한 번에 적재·처리하는 최대 로트 수. 패키지 전용이라
    // 같은 패키지의 AdminLotServiceTest가 오케스트레이션(청크 반복 횟수) 검증에 그대로 참조한다
    static final int EXPIRE_CHUNK_SIZE = 1000;

    // (API-3-04) 로트별 조회의 기본/최대 페이지 크기. AdminProductSearchCondition과 같은 값을 쓴다
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final StockLotRepository stockLotRepository;
    private final StockLotQueryRepository stockLotQueryRepository;
    private final StockMovementRepository stockMovementRepository;
    private final ProductApi productApi;
    private final ApplicationEventPublisher eventPublisher;
    private final AdminLotExpireChunkService adminLotExpireChunkService;

    public AdminLotService(StockLotRepository stockLotRepository, StockLotQueryRepository stockLotQueryRepository,
            StockMovementRepository stockMovementRepository, ProductApi productApi,
            ApplicationEventPublisher eventPublisher, AdminLotExpireChunkService adminLotExpireChunkService) {
        this.stockLotRepository = stockLotRepository;
        this.stockLotQueryRepository = stockLotQueryRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.productApi = productApi;
        this.eventPublisher = eventPublisher;
        this.adminLotExpireChunkService = adminLotExpireChunkService;
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
             * (CMP-4-04) 알려진 제약(uk_lot_request_id, fk_lot_option) 위반이 아니면 원인을 알 수
             * 없는 실패다. DB 예외 메시지는 로그에만 남기고, 클라이언트로 나가는 예외는 고정 문구의
             * StockException이라 GlobalExceptionHandler가 ErrorCode의 고정 문구만 응답에 싣는다.
             */
            log.error("event=LOT_SAVE_UNKNOWN_CONSTRAINT_VIOLATION requestId={} optionId={} cause={}",
                    request.requestId(), optionId, e.getMostSpecificCause().getMessage(), e);
            throw new StockException(StockErrorCode.UNKNOWN_CONSTRAINT_VIOLATION, e);
        } catch (PessimisticLockingFailureException e) {
            return responseOfInProgressRetry(request.requestId(), optionId, e);
        }

        StockMovement movement = StockMovement.inbound(stockLot.getId(), request.initialQty());
        stockMovementRepository.save(movement);

        // 입고는 항상 가용 수량을 늘리기만 하므로, 별도 조회 없이도 이 옵션은 이제 품절이 아니라고 확정할 수 있다
        eventPublisher.publishEvent(new OptionAvailabilityChangedEvent(optionId, false, LocalDateTime.now()));

        return AdminLotResponse.of(stockLot);
    }

    /*
     * 소비기한이 지난 AVAILABLE 로트를 찾아 EXPIRED로 전환하고 EXPIRE 이력을 남긴다(stock.md "만료
     * 로트 처리"). 하루 한 번 배치로 돌거나 관리자가 수동 호출한다.
     *
     * (DI-4-03/PERF-4-03) 대상 전체를 한 트랜잭션·영속성 컨텍스트에 적재하지 않고, 청크(최대
     * EXPIRE_CHUNK_SIZE건) 단위로 AdminLotExpireChunkService.expireChunk()를 반복 호출한다 —
     * 청크마다 별도 트랜잭션이 커밋되어 영속성 컨텍스트가 그때그때 비워진다. 처리된 행은 상태가
     * AVAILABLE→EXPIRED로 바뀌어 다음 조회 조건에서 자연히 빠지므로, 마지막 청크가
     * EXPIRE_CHUNK_SIZE보다 적게 돌아올 때까지 반복하면 전체를 다 처리한 것이다. 옵션별 품절
     * 이벤트 발행은 청크 단위로 이뤄진다(AdminLotExpireChunkService 참고, 정확성은 그대로 유지).
     *
     * (API-3-10) 이 작업은 요청 전체가 원자적이지 않다 — 명시적으로 부분 성공을 허용하는 계약이다.
     * 뒤 청크가 실패해도 이미 커밋된 앞 청크의 EXPIRED 전환은 되돌리지 않는다. 대상 전체를 한
     * 트랜잭션으로 묶으면 DI-4-03/PERF-4-03에서 피하려 한 대량 락·긴 트랜잭션이 되돌아오므로
     * 이 설계를 유지한다. 실패해도 안전한 이유: 대상 조건이 status=AVAILABLE라(INF-1-01, 멱등
     * 전이형) 이미 처리된 청크는 재실행 시 자연히 대상에서 빠져, 재호출만으로 나머지가 이어서
     * 처리된다 — 클라이언트는 실패 시 그대로 재요청하면 된다.
     */
    public AdminLotExpireResponse expireLots() {
        List<StockLot> allExpired = new ArrayList<>();
        List<StockLot> chunk;
        do {
            chunk = adminLotExpireChunkService.expireChunk(EXPIRE_CHUNK_SIZE);
            allExpired.addAll(chunk);
        } while (chunk.size() == EXPIRE_CHUNK_SIZE);

        return AdminLotExpireResponse.of(allExpired);
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
     * 로트를 폐기 처리하고 DISPOSE 변동 이력을 함께 남긴다(stock.md "폐기").
     * 같은 requestId로 재시도가 오면(API-5-07, AIP-155) 다시 차감하지 않고 최초 결과를 그대로
     * 돌려준다 — register()와 같은 이유·같은 이중 방어 구조다(사전 조회 + save() 시점 유니크 위반).
     *
     * RETURNED(재입고하지 않은 회수품)는 애초에 이 로트의 가용 수량으로 들어온 적이 없어서 수량을
     * 바꾸지 않는다 — stock.md, chk_movement_delta(DB)가 DISPOSE+RETURNED는 qty_after=qty_before를
     * 강제한다. 그 외 사유는 실제로 잔량에서 차감한다.
     *
     * 이 로트가 다 소진되고(availableQty=0) 그 옵션에 남은 AVAILABLE 로트가 없으면 품절 이벤트를
     * 발행한다 — register()가 입고 시 항상 soldOut=false를 발행하는 것의 반대 경로다.
     */
    @Transactional
    public AdminLotResponse dispose(Long lotId, Long adminId, AdminLotDisposeRequest request) {
        Optional<StockMovement> existingMovement = stockMovementRepository.findByRequestId(request.requestId());
        if (existingMovement.isPresent()) {
            return responseOfExistingDisposal(existingMovement.get(), lotId);
        }

        StockLot stockLot = findLotForUpdate(lotId);

        int qtyBefore = stockLot.getAvailableQty();
        int qtyAfter;
        if (request.disposalReason() == DisposalReason.RETURNED) {
            qtyAfter = qtyBefore;
        } else {
            if (request.quantity() > qtyBefore) {
                throw new StockException(StockErrorCode.DISPOSAL_QUANTITY_EXCEEDS_LOT);
            }
            stockLot.dispose(request.quantity());
            qtyAfter = stockLot.getAvailableQty();
        }

        StockMovement movement = StockMovement.dispose(request.requestId(), stockLot.getId(), request.quantity(),
                qtyBefore, qtyAfter, adminId, request.disposalReason(), request.reason());
        try {
            stockMovementRepository.save(movement);
        } catch (DataIntegrityViolationException e) {
            if (isConstraintViolation(e, "uk_movement_request_id")) {
                return stockMovementRepository.findByRequestId(request.requestId())
                        .map(existing -> responseOfExistingDisposal(existing, lotId))
                        .orElseThrow(() -> {
                            /*
                             * (CMP-4-04) requestId는 로그에만 남긴다. 클라이언트로 나가는 예외는
                             * StockException이라 GlobalExceptionHandler가 ErrorCode의 고정 문구만
                             * 응답에 싣는다(요청 값을 그대로 실은 문구가 밖으로 새지 않는다).
                             */
                            log.error("event=DISPOSAL_REQUEST_ID_CONFLICT_NOT_FOUND requestId={}",
                                    request.requestId());
                            return new StockException(StockErrorCode.DISPOSAL_IN_PROGRESS);
                        });
            }
            /*
             * (CMP-4-04) 알려진 제약(uk_movement_request_id) 위반이 아니면 원인을 알 수 없는
             * 실패다. DB 예외 메시지는 로그에만 남기고, 응답에는 ErrorCode의 고정 문구만 나간다.
             */
            log.error("event=DISPOSAL_SAVE_UNKNOWN_CONSTRAINT_VIOLATION requestId={} lotId={} cause={}",
                    request.requestId(), lotId, e.getMostSpecificCause().getMessage(), e);
            throw new StockException(StockErrorCode.UNKNOWN_CONSTRAINT_VIOLATION, e);
        }

        if (qtyBefore != qtyAfter && qtyAfter == 0 && !stockLotRepository.existsByProductOptionIdAndStatus(
                stockLot.getProductOptionId(), LotStatus.AVAILABLE)) {
            eventPublisher.publishEvent(
                    new OptionAvailabilityChangedEvent(stockLot.getProductOptionId(), true, LocalDateTime.now()));
        }

        return AdminLotResponse.of(stockLot);
    }

    /*
     * 같은 requestId를 다른 lotId로 재사용했으면 클라이언트의 잘못된 재사용이다 — 엉뚱한 로트의
     * 결과를 성공 응답으로 돌려주는 대신 명확한 충돌 오류로 알려준다(register()의
     * findByRequestIdOrThrow와 같은 이유).
     */
    private AdminLotResponse responseOfExistingDisposal(StockMovement movement, Long expectedLotId) {
        if (!movement.getStockLotId().equals(expectedLotId)) {
            throw new StockException(StockErrorCode.REQUEST_ID_ALREADY_USED);
        }
        return stockLotRepository.findById(movement.getStockLotId())
                .map(AdminLotResponse::of)
                .orElseThrow(() -> new IllegalStateException(
                        "폐기 이력이 가리키는 로트를 찾을 수 없다: " + movement.getStockLotId()));
    }

    /*
     * 락 대기 타임아웃/교착은 도메인 밖으로 raw 타입을 새어나가게 두지 않고 재시도 가능한 오류로 감싼다.
     * 조회 자체가 쓰기 락이라 같은 로트를 건드리는 reserve/confirm/release/expire와 경합하면
     * 여기서 실패할 수 있다 — 그 사이 beforeQty가 낡은 값이 되는 걸 막기 위한 것이라, 실패하면
     * 재시도를 안내한다.
     */
    private StockLot findLotForUpdate(Long lotId) {
        try {
            return stockLotRepository.findByIdForUpdate(lotId)
                    .orElseThrow(() -> new StockException(StockErrorCode.LOT_NOT_FOUND));
        } catch (PessimisticLockingFailureException e) {
            throw new StockException(StockErrorCode.DISPOSAL_IN_PROGRESS, e);
        }
    }

    /*
     * 상품의 로트를 소비기한 오름차순(FEFO)으로 커서 기반 페이지네이션 조회한다 (API-3-04, API-5-01).
     * productId 존재 여부는 옵션 ID 목록이 비어있는지로 판정한다 — 상품 등록 시 옵션이 최소 1개
     * 필수이고(AdminProductCreateRequest.options가 @NotEmpty) 옵션 삭제 기능이 아직 없어서,
     * 지금은 "상품이 있으면 옵션도 항상 1개 이상 있다"는 불변식이 성립한다. 옵션 삭제 기능이
     * 생기면 이 판정도 다시 봐야 한다(그때는 상품 자체의 존재 여부를 별도로 확인해야 함).
     *
     * 옵션 목록 조회와 로트 조회가 별개의 쿼리 두 번이라, 격리수준을 REPEATABLE_READ로 명시해서
     * 두 쿼리가 같은 트랜잭션 안에서 일관된 스냅샷을 보게 강제한다 — DB 기본값에 기대지 않는다.
     * 리포지토리가 pageSize + 1건을 주므로 초과분을 잘라내고 다음 페이지 여부를 판단한다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLotListResponse findAllByProduct(Long productId, boolean availableOnly, PageCursor cursor,
            int pageSize) {
        List<Long> optionIds = productApi.findOptionIds(productId);
        if (optionIds.isEmpty()) {
            throw new StockException(StockErrorCode.OPTION_NOT_FOUND);
        }

        int effectivePageSize = resolvePageSize(pageSize);
        List<StockLot> found = stockLotQueryRepository.findByProductOptionIds(optionIds, availableOnly, cursor,
                effectivePageSize);

        boolean hasNext = found.size() > effectivePageSize;
        List<StockLot> page = hasNext ? found.subList(0, effectivePageSize) : found;

        return AdminLotListResponse.of(page, nextTokenOf(page, hasNext));
    }

    private static int resolvePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    // 다음 페이지 토큰. 마지막 행의 소비기한과 id로 커서를 만든다(정렬이 expiryDate asc, id asc 고정)
    private static String nextTokenOf(List<StockLot> page, boolean hasNext) {
        if (!hasNext || page.isEmpty()) {
            return null;
        }
        StockLot last = page.get(page.size() - 1);
        return PageTokens.encode(new PageCursor(last.getId(), last.getExpiryDate().toString()));
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
