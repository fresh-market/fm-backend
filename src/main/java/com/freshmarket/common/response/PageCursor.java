package com.freshmarket.common.response;

// 커서 페이지네이션에 필요한 두 값. sortValue 는 정렬 기준값의 문자열 표현이고,
// id 는 그 값이 같을 때를 가르는 동점 처리 키다 (API-3-04)
public record PageCursor(Long id, String sortValue) {
}
