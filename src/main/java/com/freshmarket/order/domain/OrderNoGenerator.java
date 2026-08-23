package com.freshmarket.order.domain;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** DB unique 제약과 함께 주문번호 충돌을 사실상 방지하는 생성기. */
@Component
public class OrderNoGenerator {

    private static final String PREFIX = "ORD-";
    private static final int RANDOM_PART_LENGTH = 26;

    public String generate() {
        String randomPart = UUID.randomUUID().toString().replace("-", "").substring(0, RANDOM_PART_LENGTH);
        return PREFIX + randomPart;
    }
}
