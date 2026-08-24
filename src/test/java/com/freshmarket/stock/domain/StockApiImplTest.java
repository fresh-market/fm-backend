package com.freshmarket.stock.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.stock.StockOrderItemsRequest;
import com.freshmarket.stock.StockReservationItemRequest;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.stock.domain.entity.AllocationStatus;
import com.freshmarket.stock.domain.entity.LotStatus;
import com.freshmarket.stock.domain.entity.StockAllocation;
import com.freshmarket.stock.domain.entity.StockLot;
import com.freshmarket.stock.domain.exception.StockErrorCode;
import com.freshmarket.stock.domain.exception.StockException;
import com.freshmarket.stock.domain.repository.StockAllocationRepository;
import com.freshmarket.stock.domain.repository.StockLotRepository;
import com.freshmarket.stock.domain.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

// StockApiImpl의 reserve/confirm/release 성공·실패·멱등·입력검증 케이스를 검증한다
@ExtendWith(MockitoExtension.class)
class StockApiImplTest {

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private StockAllocationRepository stockAllocationRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private StockApiImpl stockApiImpl;

    @Test
    void 재고가_충분하면_한_로트에서_예약한다() {
        // given
        StockLot lot = lotWithAvailableQty(77L, 31L, 100);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockApiImpl.reserve(request);

        // then
        verify(stockLotRepository).decreaseAvailableQty(77L, 20);
        verify(stockAllocationRepository).save(any());
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 한_로트가_부족하면_다음_로트로_이어서_예약한다() {
        // given — FEFO 순서(만료일 오름차순)로 넘어온 두 로트: 첫 로트가 모자라 둘째 로트에서 나머지를 채운다
        StockLot lot1 = lotWithAvailableQty(77L, 31L, 10);
        StockLot lot2 = lotWithAvailableQty(78L, 31L, 50);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot1, lot2));
        when(stockLotRepository.decreaseAvailableQty(77L, 10)).thenReturn(1);
        when(stockLotRepository.decreaseAvailableQty(78L, 10)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockApiImpl.reserve(request);

        // then
        verify(stockLotRepository).decreaseAvailableQty(77L, 10);
        verify(stockLotRepository).decreaseAvailableQty(78L, 10);
        verify(stockAllocationRepository, times(2)).save(any());
        verify(stockMovementRepository, times(2)).save(any());
    }

    @Test
    void 이미_예약된_주문상품이면_재시도로_보고_건너뛴다() {
        // given — 같은 orderItemId로 이미 만들어진 할당이 있는 상황(재시도)
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of(mock(StockAllocation.class)));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockApiImpl.reserve(request);

        // then
        verify(stockLotRepository, never()).findByProductOptionIdAndStatusOrderByExpiryDateAsc(any(), any());
        verify(stockAllocationRepository, never()).save(any());
    }

    @Test
    void 가용_로트를_다_써도_부족하면_재고부족_오류를_던진다() {
        // given
        StockLot lot = lotWithAvailableQty(77L, 31L, 5);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 5)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.INSUFFICIENT_STOCK);
    }

    @Test
    void 조건부_UPDATE가_경합으로_실패하면_재고부족으로_처리한다() {
        // given — 읽을 땐 20개가 있었지만 UPDATE 시점엔 다른 요청이 먼저 가져가 영향받은 행이 0
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(0);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.INSUFFICIENT_STOCK);
        verify(stockAllocationRepository, never()).save(any());
    }

    @Test
    void 조건부_UPDATE가_교착으로_실패하면_처리중_오류로_감싼다() {
        // given — 이 로트가 다른 트랜잭션과 락을 주고받다 교착으로 실패한 상황
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20))
                .thenThrow(new CannotAcquireLockException("Deadlock found"));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void 동시_예약이_먼저_커밋되면_처리중_오류를_던진다() {
        // given — 조건부 UPDATE는 성공했지만, 할당 저장 시점에 uk_alloc_orderitem_lot이 걸리는 경합
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        when(stockAllocationRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry '501-77' for key 'stock_allocation.uk_alloc_orderitem_lot'"));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void 알_수_없는_제약_위반은_그대로_전파한다() {
        // given — uk_alloc_orderitem_lot이 아닌 다른 위반은 감싸지 않고 그대로 던진다
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findByOrderItemId(501L)).thenReturn(List.of());
        when(stockLotRepository.findByProductOptionIdAndStatusOrderByExpiryDateAsc(31L, LotStatus.AVAILABLE))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_alloc_qty' is violated");
        when(stockAllocationRepository.save(any())).thenThrow(unknownViolation);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(request)).isSameAs(unknownViolation);
    }

    @Test
    void 요청이_null이면_예약을_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 수량이_0_이하면_예약_요청_자체가_잘못됐다고_본다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.reserve(new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockAllocationRepository, never()).findByOrderItemId(any());
    }

    @Test
    void 예약_항목이_null이면_아무것도_하지_않는다() {
        // when
        stockApiImpl.reserve(new StockReservationRequest(9001L, null));

        // then
        verify(stockAllocationRepository, never()).findByOrderItemId(any());
    }

    @Test
    void 예약_항목이_빈_목록이면_아무것도_하지_않는다() {
        // when
        stockApiImpl.reserve(new StockReservationRequest(9001L, List.of()));

        // then
        verify(stockAllocationRepository, never()).findByOrderItemId(any());
    }

    @Test
    void 요청이_null이면_확정을_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.confirm(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확정_대상_조회_중_락_경합이_나면_처리중_오류로_감싼다() {
        // given — findByOrderItemIdInAndStatus 자체가 쓰기 락 조회라 락 대기 타임아웃/교착이 날 수 있다
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout"));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.confirm(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void RESERVED_할당을_확정하고_이력을_남긴다() {
        // given
        StockAllocation allocation = reservedAllocation(1L, 501L, 77L, 20);
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenReturn(List.of(allocation));
        when(stockLotRepository.findAllByIdForUpdate(List.of(77L)))
                .thenReturn(List.of(lotWithAvailableQty(77L, 31L, 80)));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.confirm(request);

        // then
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.CONFIRMED);
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 확정_대상이_없으면_아무것도_하지_않는다() {
        // given — 이미 CONFIRMED/RELEASED라 조회 대상에서 빠진 재시도 상황
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenReturn(List.of());
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.confirm(request);

        // then
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 확정_대상_id_목록이_비어있으면_조회조차_하지_않는다() {
        // when
        stockApiImpl.confirm(new StockOrderItemsRequest(9001L, null));
        stockApiImpl.confirm(new StockOrderItemsRequest(9001L, List.of()));

        // then
        verify(stockAllocationRepository, never()).findByOrderItemIdInAndStatus(any(), any());
    }

    @Test
    void 요청이_null이면_해제를_거부한다() {
        // when, then
        assertThatThrownBy(() -> stockApiImpl.release(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void RESERVED_할당을_해제하고_가용_수량을_복원한다() {
        // given
        StockAllocation allocation = reservedAllocation(1L, 501L, 77L, 20);
        StockLot lot = lotWithAvailableQty(77L, 31L, 80);
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenReturn(List.of(allocation));
        when(stockLotRepository.findAllByIdForUpdate(List.of(77L))).thenReturn(List.of(lot));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.release(request);

        // then
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(lot.getAvailableQty()).isEqualTo(100);
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 해제_대상이_없으면_아무것도_하지_않는다() {
        // given
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenReturn(List.of());
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when
        stockApiImpl.release(request);

        // then
        verify(stockLotRepository, never()).findAllByIdForUpdate(any());
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 해제_중_락_경합이_나면_처리중_오류로_감싼다() {
        // given — findAllByIdForUpdate가 락 대기 타임아웃/교착으로 실패한 상황
        StockAllocation allocation = reservedAllocation(1L, 501L, 77L, 20);
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenReturn(List.of(allocation));
        when(stockLotRepository.findAllByIdForUpdate(List.of(77L)))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout"));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when, then
        assertThatThrownBy(() -> stockApiImpl.release(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void 여러_로트를_해제할_때_id_오름차순으로_한_번에_잠근다() {
        // given — 할당이 가리키는 로트 id를 일부러 내림차순으로 준비해, 정렬해서 한 번에 잠그는지 확인한다.
        // 순서를 안 맞추면 동시 호출끼리 서로 다른 순서로 로트를 잠가 교착이 날 수 있다
        StockAllocation allocationA = reservedAllocation(1L, 501L, 90L, 5);
        StockAllocation allocationB = reservedAllocation(2L, 502L, 30L, 5);
        StockLot lot30 = lotWithAvailableQty(30L, 32L, 50);
        StockLot lot90 = lotWithAvailableQty(90L, 31L, 50);
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L, 502L), AllocationStatus.RESERVED))
                .thenReturn(List.of(allocationA, allocationB));
        when(stockLotRepository.findAllByIdForUpdate(List.of(30L, 90L))).thenReturn(List.of(lot30, lot90));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L, 502L));

        // when
        stockApiImpl.release(request);

        // then — 한 번의 호출로, id 오름차순(30, 90)으로 조회했는지 확인
        verify(stockLotRepository, times(1)).findAllByIdForUpdate(List.of(30L, 90L));
        assertThat(allocationA.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(allocationB.getStatus()).isEqualTo(AllocationStatus.RELEASED);
    }

    private StockLot lotWithAvailableQty(Long id, Long productOptionId, int availableQty) {
        StockLot lot = StockLot.register("req-x", productOptionId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31), availableQty);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    private StockAllocation reservedAllocation(Long id, Long orderItemId, Long stockLotId, int qty) {
        StockAllocation allocation = StockAllocation.reserve(orderItemId, stockLotId, qty);
        ReflectionTestUtils.setField(allocation, "id", id);
        return allocation;
    }
}
