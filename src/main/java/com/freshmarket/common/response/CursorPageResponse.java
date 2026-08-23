package com.freshmarket.common.response;

import java.util.List;

/*
 * 커서 기반 목록 조회의 공통 응답이며 ResponseEnvelope 의 data 자리에 들어간다.
 * PageResponse 가 오프셋 기반(페이지 번호, 전체 개수)이라면, 이건 전체 개수를 셀
 * 필요가 없는 커서 기반 목록에 쓴다. nextPageToken 이 비어 있으면 마지막 페이지다
 * (API-5-01). 토큰은 클라이언트가 해석할 수 없는 불투명 문자열이다 (API-5-02).
 */
public record CursorPageResponse<T>(
        List<T> items,
        String nextPageToken
) {

    public static <T> CursorPageResponse<T> of(List<T> items, String nextPageToken) {
        return new CursorPageResponse<>(items, nextPageToken);
    }
}