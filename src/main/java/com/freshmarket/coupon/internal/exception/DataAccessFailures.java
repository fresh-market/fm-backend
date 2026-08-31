package com.freshmarket.coupon.internal.exception;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;

/**
 * DB 실패를 <b>잠시 뒤면 될 것</b>과 <b>고쳐야 할 것</b>으로 가른다.
 *
 * <p>발급 경로가 {@code DataAccessException} 을 통째로 잡아 혼잡으로 답하면 안 된다. 그 아래에는
 * 인프라 실패만 있는 것이 아니라 SQL 문법 오류나 잘못된 사용법도 있다. 그것들까지 "잠시 후 다시"
 * 로 덮으면 <b>진짜 버그가 재시도에 묻혀 배포 뒤에도 한참 안 드러난다.</b>
 *
 * <p>읽는 쪽(서비스)과 쓰는 쪽(플러시 스레드)이 같은 규칙을 쓴다. 한쪽에만 적용하면 왜 갈렸는지를
 * 나중에 설명해야 한다.
 */
public final class DataAccessFailures {

    private DataAccessFailures() {
    }

    /**
     * 잠시 뒤면 같은 요청이 성공할 수 있는 실패인가.
     *
     * <p>스프링의 {@code TransientDataAccessException} 만으로는 모자란다. 커넥션을 못 얻은
     * 실패({@code CannotGetJdbcConnectionException})를 스프링은 비일시적으로 분류하는데,
     * <b>Multi-AZ 페일오버는 60~120초 뒤에 끝나므로 여기서는 일시적이다.</b>
     */
    public static boolean isTransient(DataAccessException e) {
        // 커넥션을 못 얻었거나 TCP 가 안 섰다
        return e instanceof DataAccessResourceFailureException
                // 보냈는데 응답이 없거나 락 경합으로 밀렸다. QueryTimeoutException 이 여기 든다
                || e instanceof TransientDataAccessException
                || e instanceof RecoverableDataAccessException;
    }
}
