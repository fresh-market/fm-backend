package com.freshmarket;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * 통합 테스트가 공유하는 MySQL 컨테이너다.
 *
 * @Container 와 @Testcontainers 를 붙이지 않는다. 그 둘은 컨테이너 수명을 테스트 클래스에 묶어
 * 클래스마다 새로 띄운다. 클래스가 늘수록 컨테이너 수가 선형으로 늘고, integrationTest 는
 * check 에 묶여 있어 커버리지 게이트를 돌릴 때마다 그 값을 치른다 (UT-1-03).
 *
 * 대신 정적 초기화로 JVM 당 한 번만 띄운다. 정리는 Testcontainers 의 Ryuk 컨테이너가
 * JVM 종료 시점에 맡으므로 stop() 을 부를 자리가 없어도 남지 않는다.
 *
 * 접속 정보가 모든 하위 클래스에서 같아지는 것이 부수 효과로 따라온다. 그 값이 Spring 의
 * 컨텍스트 캐시 키에 들어가므로, 전에는 애노테이션이 같아도 컨테이너가 달라 컨텍스트를
 * 공유하지 못하던 클래스들이 이제 하나를 나눠 쓴다.
 *
 * 베이스 패키지에 두는 이유는 PlacementIntegrationTest 가 "domain 아래에만 둔다" 와
 * "이름이 IntegrationTest 로 끝난다" 를 강제하면서 베이스 패키지만 그 대상에서 빼기 때문이다.
 */
@ActiveProfiles("integrationTest")
public abstract class IntegrationTestSupport {

    /*
     * 접속 URL 을 application.yml 이 아니라 컨테이너가 만든다.
     * 그래서 운영에 건 접속 파라미터는 여기에도 걸어야 한다. 안 그러면 JDBC 배치가 테스트에서만
     * 건별로 나가서, 실패 처리 분기가 운영과 다른 모양을 보고 검증된다.
     */
    @ServiceConnection
    protected static final MySQLContainer MYSQL = new MySQLContainer(DockerImageName.parse("mysql:8.4"))
            .withUrlParam("rewriteBatchedStatements", "true");

    /*
     * Valkey 도 같은 이유로 여기서 한 번만 띄운다.
     * 이것이 없으면 spring.data.redis 가 application.yml 의 기본값인 localhost:6379 를 보므로,
     * 개발자 기계에 컨테이너가 떠 있는지에 따라 결과가 갈린다.
     *
     * name 을 주는 이유는 compose.yaml 이 레이블을 다는 이유와 같다. Spring 이 이미지 이름으로
     * 서비스를 알아보는데 valkey 가 그 목록에 없어서, 어떤 접속인지 직접 알려야 한다.
     */
    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0-alpine")).withExposedPorts(6379);

    static {
        MYSQL.start();
        VALKEY.start();
    }
}
