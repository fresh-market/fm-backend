package com.freshmarket.admin.domain.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/*
 * DB 컬럼 값은 'ACTIVE'/'DELETED' 다 (하드 삭제 불가라 DELETED 를 비활성화 용도로 쓴다).
 * displayName 은 DB 값이 아니라 실제 의미를 드러낸다.
 */
@Getter
@RequiredArgsConstructor
public enum AdminStatus {

    ACTIVE("활성"),
    DELETED("비활성");

    private final String displayName;
}