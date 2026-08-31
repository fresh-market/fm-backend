package com.freshmarket.stock.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.freshmarket.stock.StockOrderItemsRequest;
import com.freshmarket.stock.StockReservationItemRequest;
import com.freshmarket.stock.StockReservationRequest;
import com.freshmarket.stock.internal.entity.AllocationStatus;
import com.freshmarket.stock.internal.entity.LotStatus;
import com.freshmarket.stock.internal.entity.StockAllocation;
import com.freshmarket.stock.internal.entity.StockLot;
import com.freshmarket.stock.internal.exception.StockErrorCode;
import com.freshmarket.stock.internal.exception.StockException;
import com.freshmarket.stock.internal.repository.StockAllocationRepository;
import com.freshmarket.stock.internal.repository.StockLotRepository;
import com.freshmarket.stock.internal.repository.StockMovementRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

// StockReservationService의 reserve/confirm/release 성공·실패·멱등 케이스를 검증한다(DPB-4-05)
@ExtendWith(MockitoExtension.class)
class StockReservationServiceTest {

    private static final LocalDate FIXED_EXPIRY_DATE = LocalDate.of(2026, 8, 31);

    @Mock
    private StockLotRepository stockLotRepository;

    @Mock
    private StockAllocationRepository stockAllocationRepository;

    @Mock
    private StockMovementRepository stockMovementRepository;

    @InjectMocks
    private StockReservationService stockReservationService;

    @Test
    void 재고가_충분하면_한_로트에서_예약한다() {
        // given
        StockLot lot = lotWithAvailableQty(77L, 31L, 100);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockReservationService.reserve(request);

        // then
        verify(stockLotRepository).decreaseAvailableQty(77L, 20);
        verify(stockAllocationRepository).save(any());
        verify(stockMovementRepository).save(any());
    }

    @Test
    void 한_로트가_부족하면_같은_청크의_다음_로트로_이어서_예약한다() {
        // given — FEFO 순서(만료일 오름차순)로 넘어온 두 로트: 첫 로트가 모자라 같은 청크의 둘째
        // 로트에서 나머지를 채운다. 두 로트 다 첫 청크 하나에 들어오므로 다음 청크는 필요 없다
        StockLot lot1 = lotWithAvailableQty(77L, 31L, 10);
        StockLot lot2 = lotWithAvailableQty(78L, 31L, 50);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot1, lot2));
        when(stockLotRepository.decreaseAvailableQty(77L, 10)).thenReturn(1);
        when(stockLotRepository.decreaseAvailableQty(78L, 10)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockReservationService.reserve(request);

        // then
        verify(stockLotRepository).decreaseAvailableQty(77L, 10);
        verify(stockLotRepository).decreaseAvailableQty(78L, 10);
        verify(stockAllocationRepository, times(2)).save(any());
        verify(stockMovementRepository, times(2)).save(any());
        verify(stockLotRepository, never()).findNextFefoChunk(any(), any(), any(), any(), any());
    }

    @Test
    void 로트가_청크_크기를_넘으면_다음_청크를_이어서_읽는다() {
        // given — 청크 최대치(50)를 꽉 채워도 모자라, 마지막 로트의 (만료일, id)를 커서로 다음
        // 청크를 이어서 읽는다. 두 청크에 걸쳐 로트 51개를 다 써야 요청 수량(51)을 채운다
        List<StockLot> firstChunk = IntStream.rangeClosed(1, 50)
                .mapToObj(id -> lotWithAvailableQty((long) id, 31L, 1))
                .toList();
        StockLot lotInNextChunk = lotWithAvailableQty(51L, 31L, 5);

        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(firstChunk);
        when(stockLotRepository.findNextFefoChunk(
                eq(31L), eq(LotStatus.AVAILABLE), eq(FIXED_EXPIRY_DATE), eq(50L), any(Pageable.class)))
                .thenReturn(List.of(lotInNextChunk));
        for (long id = 1; id <= 51; id++) {
            when(stockLotRepository.decreaseAvailableQty(id, 1)).thenReturn(1);
        }
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 51)));

        // when
        stockReservationService.reserve(request);

        // then — 첫 청크의 마지막 로트(id=50)를 커서로 다음 청크를 요청했는지, 51개 로트를 전부 썼는지 확인
        verify(stockLotRepository).findNextFefoChunk(
                eq(31L), eq(LotStatus.AVAILABLE), eq(FIXED_EXPIRY_DATE), eq(50L), any(Pageable.class));
        verify(stockLotRepository, times(51)).decreaseAvailableQty(any(Long.class), eq(1));
        verify(stockAllocationRepository, times(51)).save(any());
    }

    @Test
    void 다음_청크가_비어있으면_그대로_재고부족_처리한다() {
        // given — 첫 청크(50개)를 다 써도 모자란데, 이어지는 청크가 비어 있어(로트 소진) 재고부족으로 끝난다
        List<StockLot> firstChunk = IntStream.rangeClosed(1, 50)
                .mapToObj(id -> lotWithAvailableQty((long) id, 31L, 1))
                .toList();

        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(firstChunk);
        when(stockLotRepository.findNextFefoChunk(
                eq(31L), eq(LotStatus.AVAILABLE), eq(FIXED_EXPIRY_DATE), eq(50L), any(Pageable.class)))
                .thenReturn(List.of());
        for (long id = 1; id <= 50; id++) {
            when(stockLotRepository.decreaseAvailableQty(id, 1)).thenReturn(1);
        }
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 60)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.INSUFFICIENT_STOCK);
        verify(stockLotRepository, times(50)).decreaseAvailableQty(any(Long.class), eq(1));
    }

    @Test
    void 여러_옵션을_예약할_때_옵션_id_오름차순으로_처리한다() {
        // given — 요청은 옵션 90, 30 순서(내림차순)로 왔지만, 처리는 오름차순(30, 90)으로 해야
        // 옵션을 반대 순서로 예약하는 다른 요청과 로트 잠금 순서가 엇갈려 교착이 나는 걸 막는다(DI-2-03)
        StockLot lot30 = lotWithAvailableQty(1L, 30L, 50);
        StockLot lot90 = lotWithAvailableQty(2L, 90L, 50);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(any())).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(30L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot30));
        when(stockLotRepository.findFirstFefoChunk(eq(90L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot90));
        when(stockLotRepository.decreaseAvailableQty(1L, 5)).thenReturn(1);
        when(stockLotRepository.decreaseAvailableQty(2L, 5)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L, List.of(
                new StockReservationItemRequest(501L, 90L, 5),
                new StockReservationItemRequest(502L, 30L, 5)));

        // when
        stockReservationService.reserve(request);

        // then — 요청 순서(90, 30)와 무관하게 옵션 30을 먼저 조회했는지 확인
        InOrder order = inOrder(stockLotRepository);
        order.verify(stockLotRepository).findFirstFefoChunk(eq(30L), eq(LotStatus.AVAILABLE), any(Pageable.class));
        order.verify(stockLotRepository).findFirstFefoChunk(eq(90L), eq(LotStatus.AVAILABLE), any(Pageable.class));
    }

    @Test
    void 멱등성_체크는_주문_전체를_한_번에_조회한다() {
        // given — 항목이 여러 개여도 할당 존재 여부는 IN 조회 1회로 끝난다(항목마다 개별 조회하지 않는다)
        StockLot lot30 = lotWithAvailableQty(1L, 30L, 50);
        StockLot lot90 = lotWithAvailableQty(2L, 90L, 50);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(any())).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(30L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot30));
        when(stockLotRepository.findFirstFefoChunk(eq(90L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot90));
        when(stockLotRepository.decreaseAvailableQty(1L, 5)).thenReturn(1);
        when(stockLotRepository.decreaseAvailableQty(2L, 5)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L, List.of(
                new StockReservationItemRequest(501L, 90L, 5),
                new StockReservationItemRequest(502L, 30L, 5)));

        // when
        stockReservationService.reserve(request);

        // then
        verify(stockAllocationRepository, times(1)).findOrderItemIdsWithAllocation(any());
    }

    @Test
    void 이미_예약된_주문상품이면_재시도로_보고_건너뛴다() {
        // given — 같은 orderItemId로 이미 만들어진 할당이 있는 상황(재시도)
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of(501L));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when
        stockReservationService.reserve(request);

        // then
        verify(stockLotRepository, never()).findFirstFefoChunk(any(), any(), any());
        verify(stockAllocationRepository, never()).save(any());
    }

    @Test
    void 가용_로트를_다_써도_부족하면_재고부족_오류를_던진다() {
        // given — 청크가 50개 미만(로트 1개)으로 돌아와 다음 청크 없이 그대로 재고부족 처리한다
        StockLot lot = lotWithAvailableQty(77L, 31L, 5);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 5)).thenReturn(1);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.INSUFFICIENT_STOCK);
        verify(stockLotRepository, never()).findNextFefoChunk(any(), any(), any(), any(), any());
    }

    @Test
    void 조건부_UPDATE가_경합으로_실패하면_재고부족으로_처리한다() {
        // given — 읽을 땐 20개가 있었지만 UPDATE 시점엔 다른 요청이 먼저 가져가 영향받은 행이 0
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(0);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.INSUFFICIENT_STOCK);
        verify(stockAllocationRepository, never()).save(any());
    }

    @Test
    void 조건부_UPDATE가_교착으로_실패하면_처리중_오류로_감싼다() {
        // given — 이 로트가 다른 트랜잭션과 락을 주고받다 교착으로 실패한 상황
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20))
                .thenThrow(new CannotAcquireLockException("Deadlock found"));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void 동시_예약이_먼저_커밋되면_처리중_오류를_던진다() {
        // given — 조건부 UPDATE는 성공했지만, 할당 저장 시점에 uk_alloc_orderitem_lot이 걸리는 경합
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        when(stockAllocationRepository.save(any())).thenThrow(new DataIntegrityViolationException(
                "Duplicate entry '501-77' for key 'stock_allocation.uk_alloc_orderitem_lot'"));
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request))
                .isInstanceOf(StockException.class)
                .hasFieldOrPropertyWithValue("errorCode", StockErrorCode.RESERVATION_IN_PROGRESS);
    }

    @Test
    void 알_수_없는_제약_위반은_그대로_전파한다() {
        // given — uk_alloc_orderitem_lot이 아닌 다른 위반은 감싸지 않고 그대로 던진다
        StockLot lot = lotWithAvailableQty(77L, 31L, 20);
        when(stockAllocationRepository.findOrderItemIdsWithAllocation(List.of(501L))).thenReturn(Set.of());
        when(stockLotRepository.findFirstFefoChunk(eq(31L), eq(LotStatus.AVAILABLE), any(Pageable.class)))
                .thenReturn(List.of(lot));
        when(stockLotRepository.decreaseAvailableQty(77L, 20)).thenReturn(1);
        DataIntegrityViolationException unknownViolation = new DataIntegrityViolationException(
                "Check constraint 'chk_alloc_qty' is violated");
        when(stockAllocationRepository.save(any())).thenThrow(unknownViolation);
        StockReservationRequest request = new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 20)));

        // when, then
        assertThatThrownBy(() -> stockReservationService.reserve(request)).isSameAs(unknownViolation);
    }

    @Test
    void 수량이_0_이하면_예약_요청_자체가_잘못됐다고_본다() {
        // given, when, then — 검증이 DB 조회보다 먼저 끝나, 항목 하나라도 수량이 잘못되면 조회조차 하지 않는다
        assertThatThrownBy(() -> stockReservationService.reserve(new StockReservationRequest(9001L,
                List.of(new StockReservationItemRequest(501L, 31L, 0)))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(stockAllocationRepository, never()).findOrderItemIdsWithAllocation(any());
    }

    @Test
    void 예약_항목이_null이면_아무것도_하지_않는다() {
        // when
        stockReservationService.reserve(new StockReservationRequest(9001L, null));

        // then
        verify(stockAllocationRepository, never()).findOrderItemIdsWithAllocation(any());
    }

    @Test
    void 예약_항목이_빈_목록이면_아무것도_하지_않는다() {
        // when
        stockReservationService.reserve(new StockReservationRequest(9001L, List.of()));

        // then
        verify(stockAllocationRepository, never()).findOrderItemIdsWithAllocation(any());
    }

    @Test
    void 확정_대상_조회_중_락_경합이_나면_처리중_오류로_감싼다() {
        // given — findByOrderItemIdInAndStatus 자체가 쓰기 락 조회라 락 대기 타임아웃/교착이 날 수 있다
        when(stockAllocationRepository.findByOrderItemIdInAndStatus(List.of(501L), AllocationStatus.RESERVED))
                .thenThrow(new CannotAcquireLockException("Lock wait timeout"));
        StockOrderItemsRequest request = new StockOrderItemsRequest(9001L, List.of(501L));

        // when, then
        assertThatThrownBy(() -> stockReservationService.confirm(request))
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
        stockReservationService.confirm(request);

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
        stockReservationService.confirm(request);

        // then
        verify(stockMovementRepository, never()).save(any());
    }

    @Test
    void 확정_대상_id_목록이_null이면_조회조차_하지_않는다() {
        // when
        stockReservationService.confirm(new StockOrderItemsRequest(9001L, null));

        // then
        verify(stockAllocationRepository, never()).findByOrderItemIdInAndStatus(any(), any());
    }

    @Test
    void 확정_대상_id_목록이_빈_목록이면_조회조차_하지_않는다() {
        // when
        stockReservationService.confirm(new StockOrderItemsRequest(9001L, List.of()));

        // then
        verify(stockAllocationRepository, never()).findByOrderItemIdInAndStatus(any(), any());
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
        stockReservationService.release(request);

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
        stockReservationService.release(request);

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
        assertThatThrownBy(() -> stockReservationService.release(request))
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
        stockReservationService.release(request);

        // then — 한 번의 호출로, id 오름차순(30, 90)으로 조회했는지 확인
        verify(stockLotRepository, times(1)).findAllByIdForUpdate(List.of(30L, 90L));
        assertThat(allocationA.getStatus()).isEqualTo(AllocationStatus.RELEASED);
        assertThat(allocationB.getStatus()).isEqualTo(AllocationStatus.RELEASED);
    }

    private StockLot lotWithAvailableQty(Long id, Long productOptionId, int availableQty) {
        StockLot lot = StockLot.register("req-x", productOptionId, LocalDate.of(2026, 8, 1),
                FIXED_EXPIRY_DATE, availableQty);
        ReflectionTestUtils.setField(lot, "id", id);
        return lot;
    }

    private StockAllocation reservedAllocation(Long id, Long orderItemId, Long stockLotId, int qty) {
        StockAllocation allocation = StockAllocation.reserve(orderItemId, stockLotId, qty);
        ReflectionTestUtils.setField(allocation, "id", id);
        return allocation;
    }
}
