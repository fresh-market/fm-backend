package com.freshmarket.cart.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CartItemUpdateRequest(@Min(1) @Max(999) int qty) {
}