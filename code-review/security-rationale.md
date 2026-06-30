# 보안 점검 항목의 근거

이 문서는 [security.md](./security.md)의 각 점검 항목이 왜 필요한지를 구체적인 예시와 함께 설명한다.
보안 결함은 한 번 뚫리면 피해가 크고 되돌리기 어려우므로, 각 점검이 어떤 공격이나 사고를 막는지 이해하고 보는 것이 중요하다.

## 1. 입력 검증

### 왜 외부 입력을 검증하는가
외부에서 들어오는 값은 언제나 조작될 수 있다고 가정해야 한다.
검증 없이 받아들이면 잘못된 데이터가 그대로 저장되거나 이후 로직에서 예상치 못한 동작을 일으킨다.

```java
// 점검 대상: 수량을 검증 없이 받아 그대로 사용
@PostMapping("/orders")
public OrderResponse create(@RequestBody OrderRequest req) {
    // req.quantity 가 -100 이면 음수 결제, 0 이면 빈 주문이 그대로 생성됨
    return orderService.create(req);
}

// 개선: 제약을 선언하고 진입점에서 검증
public class OrderRequest {
    @Min(1) @Max(100)
    private int quantity;
}

@PostMapping("/orders")
public OrderResponse create(@RequestBody @Valid OrderRequest req) { ... }
```

입력 검증은 신뢰 경계의 가장 바깥에서 잘못된 값을 걸러내는 첫 방어선이다.

### 왜 검증을 한곳에서 일관되게 트리거하는가
검증 로직이 여기저기 흩어지면 어떤 경로는 검증되고 어떤 경로는 빠지는 구멍이 생긴다.
예를 들어 같은 OrderRequest를 받는 두 컨트롤러 중 한쪽만 `if (req.getQuantity() < 1)` 검사를 넣으면, 검사 없는 경로로 잘못된 값이 들어온다.
요청 진입점에서 `@Valid`로 일관되게 트리거하면, 어느 경로든 같은 제약이 적용되어 검증 누락을 구조적으로 막는다.

### 왜 일관된 오류 응답이 필요한가
검증 실패 응답이 엔드포인트마다 제각각이면(어디는 400에 문자열, 어디는 200에 빈 결과), 클라이언트가 경우마다 다른 처리 코드를 짜야 한다.
`@RestControllerAdvice`로 검증 실패를 한 형식으로 모으면, 클라이언트가 공통 처리 로직 하나로 대응할 수 있어 사용 편의와 예측 가능성이 함께 올라간다.

## 2. SQL 인젝션 방어

### 왜 문자열 연결로 쿼리를 만들면 안 되는가
입력값을 쿼리 문자열에 직접 붙이면, 공격자가 값 자리에 쿼리 구문을 끼워 넣을 수 있다.

```java
// 점검 대상: 입력을 문자열로 결합
String sql = "SELECT * FROM users WHERE name = '" + name + "'";

// 공격자가 name 에 다음을 넣으면:
//   ' OR '1'='1
// 쿼리가 SELECT * FROM users WHERE name = '' OR '1'='1' 가 되어 전체 사용자가 조회된다.
// name 에 '; DROP TABLE users; -- 를 넣으면 테이블 삭제까지 시도된다.
```

SQL 인젝션은 가장 오래되고 흔하면서도 데이터 유출과 파괴로 이어지는 피해가 큰 취약점이다.

### 왜 파라미터 바인딩이 방어가 되는가
파라미터 바인딩은 입력값을 쿼리 구문이 아니라 순수한 값으로만 취급한다.

```java
// 개선: 바인딩된 값은 구문이 아니라 데이터로만 처리됨
String sql = "SELECT * FROM users WHERE name = ?";
jdbcTemplate.query(sql, rowMapper, name);
// name 에 ' OR '1'='1 이 들어와도, 그 문자열과 정확히 같은 이름을 찾을 뿐 구문으로 실행되지 않는다.
```

값 안에 어떤 구문이 들어 있어도 실행되지 않고 데이터로만 다뤄지므로, 구조적으로 인젝션을 막는다.

### 왜 정렬 컬럼명을 화이트리스트로 거르는가
정렬 컬럼처럼 값이 아니라 식별자(컬럼명)가 동적으로 바뀌는 경우는 파라미터 바인딩으로 막을 수 없다.

```java
// 점검 대상: 정렬 컬럼을 입력으로 받아 그대로 결합 (바인딩 불가 영역)
String sql = "SELECT * FROM orders ORDER BY " + sortColumn;

// 개선: 허용된 컬럼만 통과시킴
Set<String> allowed = Set.of("created_at", "total_price");
if (!allowed.contains(sortColumn)) {
    throw new IllegalArgumentException("invalid sort column");
}
```

허용된 컬럼 목록과 대조하는 방식으로만 안전하게 처리할 수 있다.

## 3. 인증과 인가

### 왜 접근 제어를 빠뜨리면 안 되는가
인증은 누구인지 확인하는 것이고, 인가는 그가 그 작업을 할 권한이 있는지 확인하는 것이다.

```java
// 점검 대상: 관리자만 가능해야 하는 삭제에 인가 검사가 없음
@DeleteMapping("/admin/users/{id}")
public void delete(@PathVariable Long id) {
    userService.delete(id);   // 로그인한 일반 사용자도 호출 가능
}

// 개선: 권한을 명시적으로 요구
@PreAuthorize("hasRole('ADMIN')")
@DeleteMapping("/admin/users/{id}")
public void delete(@PathVariable Long id) { ... }
```

기능을 추가할 때 인가 점검을 깜빡하기 쉬워서, 리뷰에서 명시적으로 확인해야 한다.

### 왜 소유권을 검증하는가
식별자만으로 자원을 조회하면, 다른 사람의 식별자를 넣어 남의 데이터를 열람할 수 있다(IDOR 취약점).

```java
// 점검 대상: 주문 소유자를 확인하지 않음
@GetMapping("/orders/{orderId}")
public OrderResponse get(@PathVariable Long orderId) {
    return orderService.get(orderId);
    // /orders/1, /orders/2 ... 로 번호만 바꾸면 남의 주문이 다 보임
}

// 개선: 인증 주체와 자원 소유권을 대조
@GetMapping("/orders/{orderId}")
public OrderResponse get(@PathVariable Long orderId,
                         @AuthenticationPrincipal UserPrincipal principal) {
    return orderService.getOwnedBy(orderId, principal.getId());
}
```

### 왜 권한 검증을 클라이언트 입력에 의존하면 안 되는가
클라이언트가 보낸 값은 조작될 수 있으므로 신뢰할 수 없다.

```java
// 점검 대상: 요청 본문의 role 을 믿고 권한을 판단
if ("ADMIN".equals(req.getRole())) { ... }   // 공격자가 role=ADMIN 으로 보내면 통과

// 개선: 서버가 보관한 인증 주체의 권한으로 판단
if (principal.hasRole("ADMIN")) { ... }
```

## 4. 민감 정보 노출

### 왜 비밀번호와 토큰을 로그에 남기면 안 되는가
로그는 여러 사람이 보고 장기간 보관되며 외부 수집 시스템으로 전송되기도 한다.

```java
// 점검 대상: 요청 객체 전체를 로그로 출력 → 비밀번호가 로그 파일과 수집 시스템에 남음
log.info("login request = {}", loginRequest);  // password 필드까지 출력됨

// 개선: 식별자만 기록
log.info("login attempt for email={}", loginRequest.getEmail());
```

민감 정보가 로그에 한 번 남으면 통제 범위를 벗어나 광범위하게 노출된다. 요청 객체 전체를 무심코 찍는 코드가 흔한 사고 원인이다.

### 왜 응답 DTO에서 민감 필드를 빼는가
엔티티를 그대로 응답으로 내보내면, 외부에 보일 필요 없는 내부 필드까지 노출된다.

```java
// 점검 대상: User 엔티티를 그대로 반환 → passwordHash, internalMemo 까지 JSON 에 실림
@GetMapping("/users/{id}")
public User get(@PathVariable Long id) { return userRepository.findById(id).orElseThrow(); }

// 개선: 응답 전용 DTO 로 내보낼 필드만 명시
public record UserResponse(Long id, String name, String email) {}
```

### 왜 예외 메시지에 내부 정보를 담으면 안 되는가
상세한 오류 메시지는 공격자에게 시스템 구조나 사용 기술을 알려주는 단서가 된다.

```java
// 점검 대상: 내부 예외를 그대로 노출 → 테이블명, 쿼리, 스택을 공격자가 확인
catch (DataAccessException e) {
    return ResponseEntity.status(500).body(e.getMessage());
}

// 개선: 내부 정보는 로그에만, 외부에는 일반화된 메시지
catch (DataAccessException e) {
    log.error("query failed", e);
    return ResponseEntity.status(500).body("일시적인 오류가 발생했습니다.");
}
```

### 왜 비밀번호를 단방향 해시로 저장하는가
비밀번호를 평문이나 복호화 가능한 형태로 저장하면, 저장소가 유출됐을 때 그대로 노출된다.

```java
// 점검 대상: 평문 저장 → DB 유출 시 모든 비밀번호가 그대로 드러남
user.setPassword(rawPassword);

// 개선: 단방향 해시로 저장 → 유출돼도 원문을 되돌릴 수 없음
user.setPassword(passwordEncoder.encode(rawPassword));  // BCrypt 등
```

BCrypt 같은 단방향 해시는 원문을 되돌릴 수 없게 만들어, 유출되더라도 비밀번호 자체가 드러나는 것을 막는다.
