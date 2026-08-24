package com.freshmarket.stock.domain.dto;

import java.time.LocalDate;

// stock_lot 에서 판정에 필요한 최소 정보만 담는 내부 전용 뷰
public record StockLotView(Long productOptionId, LocalDate expiryDate) {
}