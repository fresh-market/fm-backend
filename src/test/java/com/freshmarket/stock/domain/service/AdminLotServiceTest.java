package com.freshmarket.stock.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.product.OptionAvailabilityChangedEvent;
import com.freshmarket.product.ProductApi;
import com.freshmarket.stock.domain.dto.AdminLotCreateRequest;
import com.freshmarket.stock.domain.dto.AdminLotDisposeRequest;
import com.freshmarket.stock.domain.dto.AdminLotListResponse;
import com.freshmarket.stock.domain.dto.AdminLotResponse;
import com.freshmarket.stock.domain.entity.DisposalReason;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.entity.StockMovement;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

// AdminLotService의 등록 성공과 실패 케이스를 검증한다
@ExtendWith(MockitoExtension.class)
class AdminLotServiceTest {

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @Mock
    private ProductApi productApi;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminLotService adminLotService;

    @Test
    void 입고일을_지정하면_그대로_등록한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        assertThat(result.productOptionId()).isEqualTo(31L);
        assertThat(result.receivedDate()).isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(result.expiryDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(result.initialQty()).isEqualTo(200);
        assertThat(result.availableQty()).isEqualTo(200);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        verify(stockMovementRepository).save(any());
        ArgumentCaptor<OptionAvailabilityChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(OptionAvailabilityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().productOptionId()).isEqualTo(31L);
        assertThat(eventCaptor.getValue().soldOut()).isFalse();
        assertThat(eventCaptor.getValue().occurredAt()).isNotNull();
    }

    @Test
    void 입고일을_생략하면_오늘_날짜로_등록한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", null, LocalDate.now().plusDays(10), 100);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.receivedDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void 같은_요청_식별자로_재시도하면_기존_로트를_그대로_반환한다() {
        // given — 이전 요청으로 이미 등록된 로트가 있는 상황(사전 조회에서 바로 잡힘)
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.of(existing));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockLotRepository, never()).save(any());
        verify(productApi, never()).existsOption(any(), any());
    }

    @Test
    void 다른_옵션에_재사용된_요청_식별자는_재시도로_보지_않고_새로_등록한다() {
        // given — requestId "req-1"은 옵션 31에 이미 쓰였지만, 이번 요청은 옵션 45다.
        // (productId, optionId) 조합까지 봐야 엉뚱한 옵션의 로트를 재시도 응답으로 잘못 돌려주지 않는다.
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 45L)).thenReturn(Optional.empty());
        when(productApi.existsOption(12L, 45L)).thenReturn(true);
        stubSaveAssignsId();
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 45L, request);

        // then
        assertThat(result.productOptionId()).isEqualTo(45L);
        verify(stockLotRepository).save(any());
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복되면_기존_로트를_반환한다() {
        // given — 사전 조회 시점엔 없었지만, save() 직전에 동시 재시도가 먼저 커밋을 마친 경합 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'stock_lot.uk_lot_request_id'"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 다른_옵션에_이미_사용된_요청_식별자면_충돌_오류를_던진다() {
        // given — save() 시점에 uk_lot_request_id 위반이 났는데, 같은 (requestId, optionId) 조합으로
        // 재조회해도 없다면 그 requestId는 다른 옵션 소속이라는 뜻이다(클라이언트의 잘못된 재사용)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.empty());
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'req-1' for key 'stock_lot.uk_lot_request_id'"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.REQUEST_ID_ALREADY_USED);
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서_찾으면_기존_로트를_반환한다() {
        // given — save()가 유니크 위반이 아니라 락 대기 타임아웃으로 실패했지만, 그 사이 첫 요청이 커밋을 마친 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        StockLot existing = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 17),
                LocalDate.of(2026, 8, 31), 200);
        ReflectionTestUtils.setField(existing, "id", 77L);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(stockLotRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when
        AdminLotResponse result = adminLotService.register(12L, 31L, request);

        // then
        assertThat(result.stockLotId()).isEqualTo(77L);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 락_대기_타임아웃_후_재조회에서도_못_찾으면_처리중_오류를_던진다() {
        // given — 첫 요청이 타임아웃 안에도 여전히 처리 중인 상황(비정상적으로 느린 경우)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.findByRequestIdAndProductOptionId("req-1", 31L)).thenReturn(Optional.empty());
        when(stockLotRepository.save(any())).thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.REGISTRATION_IN_PROGRESS);
    }

    @Test
    void 존재하지_않는_옵션으로_등록하면_실패한다() {
        // given
        when(productApi.existsOption(12L, 999L)).thenReturn(false);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", null, LocalDate.now().plusDays(10), 100);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 999L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    void 등록_중_옵션이_동시에_삭제되면_존재하지_않는_옵션으로_응답한다() {
        // given — 사전 확인(existsOption) 통과 직후, save() 시점엔 옵션이 이미 삭제된 경합 상황
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        when(stockLotRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Cannot add or update a child row: a foreign key constraint fails "
                        + "(`freshmarket`.`stock_lot`, CONSTRAINT `fk_lot_option` "
                        + "FOREIGN KEY (`product_option_id`) REFERENCES `product_option` (`product_option_id`))"));
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 알_수_없는_제약_위반은_감싸서_던진다() {
        // given — fk_lot_option이 아닌 다른 위반(예: chk_lot_qty처럼 별도로 변환하지 않는 제약)
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_lot_qty' is violated");
        when(stockLotRepository.save(any())).thenThrow(unknownViolation);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 31), 200);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(unknownViolation);
    }

    @Test
    void 소비기한이_입고일보다_이르면_실패한다() {
        // given
        when(productApi.existsOption(12L, 31L)).thenReturn(true);
        AdminLotCreateRequest request = new AdminLotCreateRequest(
                "req-1", LocalDate.of(2026, 8, 20), LocalDate.of(2026, 8, 10), 100);

        // when, then
        assertThatThrownBy(() -> adminLotService.register(12L, 31L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.EXPIRY_BEFORE_RECEIVED);
        verify(stockLotRepository, never()).save(any());
    }

    @Test
    void 상품의_로트_전체를_소비기한_오름차순으로_조회한다() {
        // given
        when(productApi.findOptionIds(12L)).thenReturn(List.of(31L, 45L));
        StockLot lot1 = lotOf(31L, LocalDate.of(2026, 8, 31), 77L);
        StockLot lot2 = lotOf(45L, LocalDate.of(2026, 9, 10), 78L);
        when(stockLotRepository.findByProductOptionIdInOrderByExpiryDateAsc(List.of(31L, 45L)))
                .thenReturn(List.of(lot1, lot2));

        // when
        AdminLotListResponse result = adminLotService.findAllByProduct(12L, false);

        // then
        assertThat(result.lots()).hasSize(2);
        assertThat(result.lots().get(0).stockLotId()).isEqualTo(77L);
        assertThat(result.lots().get(1).stockLotId()).isEqualTo(78L);
    }

    @Test
    void availableOnly가_true면_판매_가능_로트만_조회한다() {
        // given
        when(productApi.findOptionIds(12L)).thenReturn(List.of(31L));
        StockLot lot = lotOf(31L, LocalDate.of(2026, 8, 31), 77L);
        when(stockLotRepository.findByProductOptionIdInAndStatusOrderByExpiryDateAsc(
                List.of(31L), LotStatus.AVAILABLE)).thenReturn(List.of(lot));

        // when
        AdminLotListResponse result = adminLotService.findAllByProduct(12L, true);

        // then
        assertThat(result.lots()).hasSize(1);
        verify(stockLotRepository, never()).findByProductOptionIdInOrderByExpiryDateAsc(any());
    }

    @Test
    void 상품에_옵션이_하나도_없으면_상품_없음으로_실패한다() {
        // given — 상품 등록 시 옵션이 최소 1개 필수라, 옵션 ID 목록이 비어있다는 건 상품 자체가 없다는 뜻이다
        when(productApi.findOptionIds(999L)).thenReturn(List.of());

        // when, then
        assertThatThrownBy(() -> adminLotService.findAllByProduct(999L, false))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.OPTION_NOT_FOUND);
        verify(stockLotRepository, never()).findByProductOptionIdInOrderByExpiryDateAsc(any());
        verify(stockLotRepository, never()).findByProductOptionIdInAndStatusOrderByExpiryDateAsc(any(), any());
    }

    // ---- dispose() ----

    @Test
    void 부분_폐기하면_남은_수량만큼_유지되고_품절_이벤트는_안_뜬다() {
        // given
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 100);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, "파손분");

        // when
        AdminLotResponse result = adminLotService.dispose(77L, 5L, request);

        // then
        assertThat(result.availableQty()).isEqualTo(70);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        verify(stockMovementRepository).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 전량_폐기하고_남은_가용_로트가_없으면_품절_이벤트를_발행한다() {
        // given
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 100);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(false);
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 100, DisposalReason.EXPIRED, null);

        // when
        AdminLotResponse result = adminLotService.dispose(77L, 5L, request);

        // then
        assertThat(result.availableQty()).isEqualTo(0);
        assertThat(result.status()).isEqualTo("DISPOSED");
        ArgumentCaptor<OptionAvailabilityChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(OptionAvailabilityChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().productOptionId()).isEqualTo(31L);
        assertThat(eventCaptor.getValue().soldOut()).isTrue();
    }

    @Test
    void 전량_폐기해도_다른_가용_로트가_있으면_품절_이벤트를_발행하지_않는다() {
        // given
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 100);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        when(stockLotRepository.existsByProductOptionIdAndStatus(31L, LotStatus.AVAILABLE)).thenReturn(true);
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 100, DisposalReason.EXPIRED, null);

        // when
        adminLotService.dispose(77L, 5L, request);

        // then
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void RETURNED_폐기는_수량을_바꾸지_않고_수량_초과_검증도_건너뛴다() {
        // given — 회수품 폐기는 애초에 가용 재고로 들어온 적이 없어 잔량(50)보다 큰 수량(200)도 허용된다
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 50);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 200, DisposalReason.RETURNED, null);

        // when
        AdminLotResponse result = adminLotService.dispose(77L, 5L, request);

        // then — qtyBefore == qtyAfter (chk_movement_delta), 로트 상태도 그대로 AVAILABLE
        assertThat(result.availableQty()).isEqualTo(50);
        assertThat(result.status()).isEqualTo("AVAILABLE");
        verify(stockMovementRepository).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void 같은_요청_식별자로_재시도하면_다시_차감하지_않고_기존_결과를_반환한다() {
        // given — 이전 요청으로 이미 처리된 폐기 이력이 있는 상황(사전 조회에서 바로 잡힘)
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 70);
        ReflectionTestUtils.setField(lot, "id", 77L);
        StockMovement existing = StockMovement.dispose("dispose-1", 77L, 30, 100, 70, 5L, DisposalReason.DAMAGED,
                "파손분");
        when(stockMovementRepository.findByRequestId("dispose-1")).thenReturn(Optional.of(existing));
        when(stockLotRepository.findById(77L)).thenReturn(Optional.of(lot));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, "파손분");

        // when
        AdminLotResponse result = adminLotService.dispose(77L, 5L, request);

        // then
        assertThat(result.availableQty()).isEqualTo(70);
        verify(stockLotRepository, never()).findByIdForUpdate(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 다른_로트에_이미_사용된_요청_식별자면_충돌_오류를_던진다() {
        // given — requestId "dispose-1"은 로트 77에 이미 쓰였지만, 이번 요청은 로트 88이다
        StockMovement existing = StockMovement.dispose("dispose-1", 77L, 30, 100, 70, 5L, DisposalReason.DAMAGED,
                null);
        when(stockMovementRepository.findByRequestId("dispose-1")).thenReturn(Optional.of(existing));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 10, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(88L, 5L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.REQUEST_ID_ALREADY_USED);
        verify(stockLotRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복되면_기존_결과를_반환한다() {
        // given — 사전 조회 시점엔 없었지만, save() 직전에 동시 재시도가 먼저 커밋을 마친 경합 상황
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 70);
        ReflectionTestUtils.setField(lot, "id", 77L);
        StockLot lockedLot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                100);
        ReflectionTestUtils.setField(lockedLot, "id", 77L);
        when(stockMovementRepository.findByRequestId("dispose-1")).thenReturn(Optional.empty(),
                Optional.of(StockMovement.dispose("dispose-1", 77L, 30, 100, 70, 5L, DisposalReason.DAMAGED, null)));
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lockedLot));
        when(stockMovementRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'dispose-1' for key 'stock_movement.uk_movement_request_id'"));
        when(stockLotRepository.findById(77L)).thenReturn(Optional.of(lot));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, null);

        // when
        AdminLotResponse result = adminLotService.dispose(77L, 5L, request);

        // then
        assertThat(result.availableQty()).isEqualTo(70);
    }

    @Test
    void 폐기_저장_중_알_수_없는_제약_위반은_감싸서_던진다() {
        // given — uk_movement_request_id가 아닌 다른 위반(예: chk_movement_delta처럼 별도로 변환하지 않는 제약)
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 70);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_movement_delta' is violated");
        when(stockMovementRepository.save(any())).thenThrow(unknownViolation);
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(77L, 5L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(unknownViolation);
    }

    @Test
    void 저장_중_요청_식별자가_동시에_중복됐는데_재조회에도_없으면_상태_오류를_던진다() {
        // given — uk_movement_request_id 위반 직후 재조회했는데도 없는, 있을 수 없는 상황을 방어한다
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 70);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockMovementRepository.findByRequestId("dispose-1")).thenReturn(Optional.empty(), Optional.empty());
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        when(stockMovementRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry 'dispose-1' for key 'stock_movement.uk_movement_request_id'"));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(77L, 5L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispose-1");
    }

    @Test
    void 폐기_이력이_가리키는_로트를_찾을_수_없으면_상태_오류를_던진다() {
        // given — 폐기 이력은 있는데 그 이력이 가리키는 로트가 사라진, 있을 수 없는 상황을 방어한다
        StockMovement existing = StockMovement.dispose("dispose-1", 77L, 30, 100, 70, 5L, DisposalReason.DAMAGED,
                null);
        when(stockMovementRepository.findByRequestId("dispose-1")).thenReturn(Optional.of(existing));
        when(stockLotRepository.findById(77L)).thenReturn(Optional.empty());
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 30, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(77L, 5L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("77");
    }

    @Test
    void 존재하지_않는_로트를_폐기하면_실패한다() {
        // given
        when(stockLotRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 10, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(999L, 5L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.LOT_NOT_FOUND);
    }

    @Test
    void 폐기_수량이_잔량을_넘으면_실패한다() {
        // given
        StockLot lot = StockLot.register("req-1", 31L, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), 50);
        ReflectionTestUtils.setField(lot, "id", 77L);
        when(stockLotRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(lot));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 51, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(77L, 5L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.DISPOSAL_QUANTITY_EXCEEDS_LOT);
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 폐기_중_락_경합이_발생하면_처리중_오류를_던진다() {
        // given
        when(stockLotRepository.findByIdForUpdate(77L))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
        AdminLotDisposeRequest request = new AdminLotDisposeRequest("dispose-1", 10, DisposalReason.DAMAGED, null);

        // when, then
        assertThatThrownBy(() -> adminLotService.dispose(77L, 5L, request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.DISPOSAL_IN_PROGRESS);
    }

    private StockLot lotOf(Long optionId, LocalDate expiryDate, Long id) {
        StockLot lot = StockLot.register("req-" + id, optionId, LocalDate.of(2026, 8, 1), expiryDate, 100);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    // 실제 저장이 없는 단위 테스트에서 JPA가 채워줄 생성 ID를 대신 채워준다.
    // StockMovement.inbound()가 stockLot.getId()를 그대로 넘겨받아 써야 해서 필요하다.
    private void stubSaveAssignsId() {
        when(stockLotRepository.save(any())).thenAnswer(invocation -> {
            StockLot stockLot = invocation.getArgument(0);
            ReflectionTestUtils.setField(stockLot, "id", 77L);
            return stockLot;
        });
    }
}
