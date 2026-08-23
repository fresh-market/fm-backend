package com.freshmarket.member.domain.entity;

public enum MemberStatus {
    /** 카카오 최초 로그인 직후 — 필수 추가정보를 아직 안 받은 상태. */
    PENDING_PROFILE,
    ACTIVE,
    /** 관리자에 의한 이용 제한. DDL에 맞춰 값만 추가한 상태 — 전이 흐름은 아직 미구현. */
    BLOCKED,
    WITHDRAWN,
}
