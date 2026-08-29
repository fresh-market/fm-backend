package com.freshmarket;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/*
 * Redis/Valkey까지 실제로 검증해야 하는 통합 테스트 전용 지원 클래스다.
 * 공용 IntegrationTestSupport에 Valkey를 넣으면 MySQL만 필요한 모든 통합 테스트까지
 * 캐시 컨테이너를 띄우게 되므로, Redis가 테스트 대상인 클래스만 이 지원 클래스를 상속한다.
 *
 * 운영/로컬과 같은 valkey/valkey:9.0-alpine을 사용한다. 이미지 이름이 redis가 아니므로
 * @ServiceConnection의 name을 redis로 명시해 Spring Boot가 RedisConnectionDetails를 만든다.
 */
public abstract class RedisIntegrationTestSupport extends IntegrationTestSupport {

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> VALKEY =
            new GenericContainer<>(DockerImageName.parse("valkey/valkey:9.0-alpine"))
                    .withExposedPorts(6379);

    static {
        VALKEY.start();
    }
}