package com.freshmarket.config;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * QueryDSL 실행 진입점인 JPAQueryFactory 를 빈으로 등록한다.
 * 리포지토리마다 EntityManager 로 직접 만들면 생성 코드가 흩어지고 트랜잭션 경계가 흐려진다.
 */
@Configuration
public class QuerydslConfig {

    /*
     * 프록시가 주입되어 트랜잭션마다 그 트랜잭션의 영속성 컨텍스트로 연결된다.
     * 싱글턴 빈이 EntityManager 를 들고 있어도 스레드마다 다른 컨텍스트를 보는 이유가 이것이다.
     */
    @PersistenceContext
    private EntityManager entityManager;

    @Bean
    public JPAQueryFactory jpaQueryFactory() {
        return new JPAQueryFactory(entityManager);
    }
}