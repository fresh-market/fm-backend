package com.freshmarket.coupon.internal.redis;

/**
 * 순번을 줄 수 없다는 뜻이다. Redis 가 답하지 않았거나, 연속해서 깨져 회로가 열려 있다.
 *
 * <p>둘을 한 타입으로 묶은 이유가 있다. 호출자가 할 일이 같기 때문이다. <b>재고는 남아 있을 수
 * 있으므로 소진이 아니고, 다시 시도할 값이 있으므로 혼잡으로 답한다.</b> 어느 쪽인지 갈라야 할
 * 호출자가 없으니 타입을 둘로 나눌 이유도 없다.
 *
 * <p>회로 라이브러리의 예외를 여기서 감싸 밖으로 안 내보낸다. 서비스가 그 타입을 알면 라이브러리를
 * 바꿀 때 서비스까지 따라 고쳐야 한다.
 */
public class CouponSeqUnavailableException extends RuntimeException {

    public CouponSeqUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
