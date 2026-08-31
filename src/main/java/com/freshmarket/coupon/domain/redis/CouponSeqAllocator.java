package com.freshmarket.coupon.domain.redis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.freshmarket.coupon.domain.issue.CouponIssueProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * 요청 스레드가 Redis 에서 순번을 받아 오는 자리다. 이 클래스는 쿠폰과 회원의 식별자만 받고
 * 엔티티는 전혀 모른다.
 *
 * <p>이 클래스는 키 넷(seq, free, counter, pending)을 <b>한 번에</b> 다뤄야 해서 Lua 스크립트
 * 하나로 처리한다. 나눠 부르면 그 사이에 다른 요청이 끼어들어 같은 번호가 두 번 나갈 수 있다.
 * 스크립트가 무엇을 어떤 순서로 보는지는 {@code docs/coupon/coupon.md} 3장에 있다.
 *
 * <p>회로는 <b>Redis 호출에만</b> 건다. 호출이 연속해서 깨지면 회로가 열리고, 그때부터 이
 * 클래스는 아무도 Redis 로 안 보내고 {@link CouponSeqUnavailableException} 을 던진다.
 * 회로를 발급 기능 전체에 걸면 <b>이미 큐에 들어간 요청까지 버리게 된다.</b> 큐에 든 것은
 * 순번과 회원과 응답 통로뿐이고 플러시 스레드는 DB 에만 쓰므로, Redis 가 죽어도 그 요청들은
 * 그대로 발급된다({@code docs/coupon/coupon.md} 3장).
 *
 * <p>Redis 가 죽었을 때 순번을 대신 내줄 곳은 두지 않았다. 전역 순서기가 사라진 상태라 순서를
 * 정할 수단이 없고, 무엇보다 <b>평소에 한 번도 안 도는 코드를 장애가 난 순간에 처음 태우는 것이
 * 장애를 키운다.</b>
 */
@Slf4j
@Component
public class CouponSeqAllocator {

    private static final String SCRIPT_PATH = "redis/scripts/coupon-issue-seq.lua";

    private static final String COMMITTED_DELIMITER = ":";
    private static final String SOLD_OUT = "-1";
    private static final String NOT_PREPARED = "-2";
    private static final String SOLD_OUT_FINAL = "-3";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<String> allocateScript;
    private final Duration reclaimAfter;
    private final CircuitBreaker circuitBreaker;

    public CouponSeqAllocator(StringRedisTemplate redisTemplate,
                              CouponIssueProperties properties,
                              CircuitBreakerRegistry circuitBreakers) {
        this.redisTemplate = redisTemplate;
        this.reclaimAfter = properties.reclaimAfter();
        this.allocateScript = loadAllocateScript();
        /*
         * 회로를 여기서 만들지 않고 레지스트리에서 받는다.
         * 값은 application.yml 의 resilience4j.circuitbreaker.instances.couponSeq 가 갖고,
         * 지표도 스타터가 함께 붙여 준다. 이름이 그 설정 키와 같아야 짝이 맞는다.
         */
        this.circuitBreaker = circuitBreakers.circuitBreaker("couponSeq");

        /*
         * 이 회로는 지금 호출을 막지 않는다. 재서 그렇게 정했다.
         *
         * 2026-08-30 부하 시험에서 회로만 끄고 같은 조건으로 돌렸더니 발급이 196건에서
         * 5,513건으로 늘었다. 리팩토링 뒤 회차에서는 seq 실패 32,799건 중 29,016건이 회로가
         * 막은 것이었고, 진짜 Redis 실패는 3,783건뿐이었다. 그 회차는 연결 실패가 6,018건으로
         * 여섯 회차 중 가장 적어 기계가 제일 멀쩡했는데도 그랬다.
         *
         * 원인은 회로가 읽는 신호에 있다. 지연 하나에 Redis 건강과 이 JVM 의 GC 정지가 섞여
         * 있는데, 정지가 104~330밀리초라 느림 기준 50밀리초를 늘 넘는다. Redis 가 멀쩡해도
         * 정지 한 번이 그 사이의 호출을 전부 느린 것으로 만든다.
         *
         * DISABLED 가 아니라 METRICS_ONLY 를 쓴다. 둘 다 호출을 안 막지만 이쪽은 실패율과
         * 느림 비율을 계속 기록한다. 그 값이 있어야 "이제 켜도 되는가" 를 나중에 판단할 수 있다.
         *
         * 켜기 전에 풀어야 할 것은 느림 판정과 GC 정지를 가르는 방법이다. 지금 기준으로는
         * 둘이 구분되지 않는다. DB 쓰기 회로(couponWrite)는 그대로 켜 둔다. 그쪽은 실패를
         * 예외 목록으로 세고 느림 기준도 150밀리초라 정지에 덜 휘둘린다.
         */
        this.circuitBreaker.transitionToMetricsOnlyState();
    }

    /*
     * 이 생성자가 스크립트 파일을 기동 때 미리 다 읽어 둔다.
     * setLocation 으로 걸어 두면 스프링이 첫 실행 때 파일을 읽으므로, 패키징이 어긋났을 때
     * 이벤트의 첫 요청에서야 그 사실을 알게 된다. 여기서 읽으면 대신 기동이 실패한다.
     */
    private static RedisScript<String> loadAllocateScript() {
        ClassPathResource resource = new ClassPathResource(SCRIPT_PATH);
        try (InputStream in = resource.getInputStream()) {
            return RedisScript.of(new String(in.readAllBytes(), StandardCharsets.UTF_8), String.class);
        } catch (IOException e) {
            throw new IllegalStateException(SCRIPT_PATH + " 를 읽지 못했다", e);
        }
    }

    /**
     * 이 회원에게 순번 하나를 준다. 이미 번호를 받은 회원이면 스크립트가 새 번호를 태우지 않고
     * 그 회원이 갖고 있던 번호를 그대로 돌려준다.
     *
     * @param issueLimit 쿠폰의 총 수량. 스크립트가 이 값을 넘는 순번은 내주지 않는다
     * @return 네 갈래 중 하나. 서비스가 갈래마다 다른 응답을 낸다({@link SeqOutcome})
     */
    public SeqOutcome allocate(long couponId, long memberId, int issueLimit) {
        try {
            String raw = circuitBreaker.executeCallable(() -> redisTemplate.execute(
                    allocateScript,
                    List.of(CouponSeqKeys.seq(couponId), CouponSeqKeys.free(couponId),
                            CouponSeqKeys.counter(couponId), CouponSeqKeys.pending(couponId)),
                    String.valueOf(memberId),
                    String.valueOf(issueLimit),
                    String.valueOf(reclaimAfter.toMillis())
            ));
            return parse(raw);
        } catch (CallNotPermittedException e) {
            throw new CouponSeqUnavailableException("순번 확보 회로가 열려 있다", e);
        } catch (DataAccessException e) {
            throw new CouponSeqUnavailableException("Redis 가 순번을 주지 못했다", e);
        } catch (Exception e) {
            /*
             * 회로의 executeCallable 이 검사 예외까지 던질 수 있게 선언돼 있어 여기서 받아 준다.
             * 다만 스크립트와 이 클래스가 어긋났을 때 나는 IllegalStateException 은 Redis 장애가
             * 아니므로 혼잡으로 덮지 않고 그대로 되던진다.
             */
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new CouponSeqUnavailableException("순번 확보가 실패했다", e);
        }
    }

    /**
     * 지금까지 나간 순번 수를 읽는다. 발급 현황 조회가 부른다.
     *
     * <p>단순 {@code GET} 이라 순번 확보 스크립트와 자원을 다투지 않는다. 회로도 안 씌운다 —
     * 이 값을 못 읽어도 발급 자체는 막히지 않으므로, 실패하면 호출부가 DB 값으로 대체하면 된다.
     *
     * @return 카운터 키가 없으면(이벤트가 열린 적 없거나 이미 닫혀 키가 지워졌다) 빈 값
     */
    public Optional<Integer> currentIssuedCount(long couponId) {
        String raw = redisTemplate.opsForValue().get(CouponSeqKeys.counter(couponId));
        return raw != null ? Optional.of(Integer.parseInt(raw)) : Optional.empty();
    }

    private SeqOutcome parse(String raw) {
        if (NOT_PREPARED.equals(raw)) {
            return new SeqOutcome.NotPrepared();
        }
        if (SOLD_OUT.equals(raw)) {
            return new SeqOutcome.SoldOut();
        }
        if (SOLD_OUT_FINAL.equals(raw)) {
            return new SeqOutcome.SoldOutFinal();
        }

        /*
         * 스크립트는 언제나 문자열을 돌려준다. 그러니 여기까지 왔다면 스크립트와 이 클래스가
         * 서로 다른 약속을 보고 있는 것이다. 혼잡으로 흘려보내면 그 어긋남이 사용자의 재시도에
         * 묻혀 아무도 모르게 되므로 그대로 터뜨린다.
         */
        if (raw == null) {
            throw new IllegalStateException(SCRIPT_PATH + " 가 nil 을 돌려줬다");
        }

        // 확정 표시("6:1")가 붙어 있으면 그 회원의 행이 DB 에 커밋까지 끝난 것이다
        int delimiter = raw.indexOf(COMMITTED_DELIMITER);
        if (delimiter < 0) {
            return new SeqOutcome.Allocated(Integer.parseInt(raw));
        }
        return new SeqOutcome.AlreadyIssued(Integer.parseInt(raw.substring(0, delimiter)));
    }
}
