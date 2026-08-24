package com.freshmarket.common.query;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.EnumPath;
import com.querydsl.core.types.dsl.Expressions;

/*
 * 상태/유형처럼 COLLATE utf8mb4_0900_as_cs 를 붙인 컬럼(V1__init_schema.sql 상단 규칙)을
 * QueryDSL 로 .eq() 비교하면 "Illegal mix of collations" 에러가 난다.
 *
 * Hibernate 공식 문서(6/7 공통)의 collate 함수 문법은 collate(expr as collation) 이다.
 * 콤마가 아니라 as 키워드로 인자를 구분한다는 점이 다른 HQL 함수와 다르다.
 */
public final class CollationExpressions {

    private static final String AS_CS = "utf8mb4_0900_as_cs";

    private CollationExpressions() {
    }

    // as_cs 콜레이션이 붙은 enum 컬럼과 값을 비교한다
    public static <E extends Enum<E>> BooleanExpression equalsAsCs(EnumPath<E> column, E value) {
        if (value == null) {
            return null;
        }
        return Expressions.stringTemplate(
                        "collate({0} as " + AS_CS + ")", column.stringValue())
                .eq(value.name());
    }
}