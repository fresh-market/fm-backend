# 보안 리뷰 가이드

인증, 인가, 입력 처리, 보안 설정 변경에 적용한다.
입력 검증, 인젝션 방어, 접근 제어, 민감 정보 노출을 다룬다.

## 1. 입력 검증

외부 입력을 신뢰하지 않고 검증하는지 본다.

점검 항목
* 요청 DTO에 검증 애너테이션을 적용했는가
* Controller에서 검증을 트리거하는가(@Valid)
* 검증 실패 시 일관된 오류 응답을 반환하는가

```java
// 점검 대상: 입력 검증 없음
@PostMapping("/users")
public UserResponse create(@RequestBody UserRequest request) {
    return userService.create(request);
}

// 개선: 제약 애너테이션과 @Valid 적용
public class UserRequest {
    @NotBlank
    private String name;

    @Email
    private String email;
}

@PostMapping("/users")
public UserResponse create(@RequestBody @Valid UserRequest request) {
    return userService.create(request);
}
```

## 2. SQL 인젝션 방어

쿼리에 입력값을 문자열로 직접 결합하지 않는지 본다.

점검 항목
* 파라미터 바인딩을 사용하는가
* 동적 정렬 컬럼명 등을 화이트리스트로 검증하는가
* 네이티브 쿼리에 사용자 입력을 직접 삽입하지 않는가

```java
// 점검 대상: 문자열 연결로 SQL 구성 (인젝션 취약)
String sql = "SELECT * FROM users WHERE name = '" + name + "'";

// 개선: 파라미터 바인딩 사용
String sql = "SELECT * FROM users WHERE name = ?";
jdbcTemplate.query(sql, rowMapper, name);
```

## 3. 인증과 인가

접근 제어가 누락되지 않았는지 본다.

점검 항목
* 권한이 필요한 엔드포인트에 접근 제어가 적용되어 있는가
* 본인 자원만 접근 가능해야 하는 경우 소유권을 검증하는가
* 권한 검증을 클라이언트 입력에만 의존하지 않는가

```java
// 점검 대상: 요청자가 자원 소유자인지 확인하지 않음
@GetMapping("/orders/{orderId}")
public OrderResponse get(@PathVariable Long orderId) {
    return orderService.get(orderId);  // 남의 주문도 조회 가능
}

// 개선: 인증 주체와 자원 소유권 대조
@GetMapping("/orders/{orderId}")
public OrderResponse get(@PathVariable Long orderId,
                         @AuthenticationPrincipal UserPrincipal principal) {
    return orderService.getOwnedBy(orderId, principal.getId());
}
```

## 4. 민감 정보 노출

비밀번호, 토큰 등이 외부로 새지 않는지 본다.

점검 항목
* 비밀번호나 토큰이 로그에 출력되지 않는가
* 응답 DTO에 민감 필드가 포함되지 않는가
* 예외 메시지에 내부 구조나 민감 정보가 노출되지 않는가
* 비밀번호를 단방향 해시(BCrypt 등)로 저장하는가

```java
// 점검 대상: 요청 객체 전체를 로그로 출력 (비밀번호 노출)
log.info("login request = {}", loginRequest);

// 개선: 민감 필드를 제외하고 식별자만 기록
log.info("login attempt for email={}", loginRequest.getEmail());
```
