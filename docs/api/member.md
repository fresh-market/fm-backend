# 회원

회원 정보와 배송지, 그리고 관리자의 회원 관리다.
로그인은 [auth.md](./auth.md), 관리자 계정 자체는 [admin.md](./admin.md) 에 있다.

## 회원 정보

### 내 정보 조회

```
GET /v1/members/me
```

싱글톤 리소스다 (`API-2-07`). 자기 정보를 보는 데 식별자를 받지 않는다.
**클라이언트가 보낸 식별자가 아니라 토큰의 주체로 조회한다** (`SEC-1-02`).

```json
{
  "code": "SUCCESS",
  "data": {
    "memberId": 1,
    "nickname": "홍길동",
    "name": "홍*동",
    "email": "hon***@example.com",
    "phone": "010-****-5678",
    "status": "ACTIVE",
    "grade": { "gradeId": 2, "name": "실버" },
    "marketingAgreed": true
  }
}
```

**이름, 이메일, 연락처는 마스킹해서 내보낸다.**

### 내 정보 수정

```
PATCH /v1/members/me
```

부분 수정이다. 보낸 필드만 바뀐다 (`API-3-06`).

| 필드 | 제약 |
|---|---|
| `name` | 50자 이하 |
| `nickname` | 50자 이하 |
| `email` | 이메일 형식 |
| `phone` | 20자 이하 |
| `marketingAgreed` | 불리언 |

**비밀번호는 없다.** 카카오가 관리한다. `providerUserId` 도 바꿀 수 없다.

`PENDING_PROFILE` 상태에서 필수 항목을 채우면 `ACTIVE` 로 바뀐다.

**회원 행이 만들어질 때 장바구니도 함께 만들어진다.** 회원당 하나이고 생성 API 가 없다 ([order.md](./order.md) 참고).

### 탈퇴

```
POST /v1/members/me:withdraw
```

**카카오 재인증(`prompt=login`) 을 먼저 통과해야 한다.** 비밀번호가 없는 서비스라(카카오가
계정을 관리) "비밀번호 재입력" 같은 재인증 수단이 없다 — 그 대신 파괴적 동작(계정 삭제) 앞에서
카카오 로그인 화면을 강제로 다시 띄워, 지금 이 요청을 보낸 사람이 정말 이 카카오 계정을 지금
다시 조작할 수 있는 사람인지 확인한다.

**(2026-08-18) 그래서 요청 본문이 문서 초안보다 필드가 하나 더 있다.** `reason` 만으로는 이
재인증을 검증할 수 없어서, 재인증에서 받은 `authorizationCode`/`state`도 같이 실어 보낸다.

```json
{
  "reason": "서비스를 더 이상 이용하지 않음",
  "authorizationCode": "...",
  "state": "..."
}
```

전체 흐름:

```
1. 프론트가 GET /v1/auth/kakao/authorize?reauth=true 호출
   -> 서버가 새 state/nonce를 발급하고 카카오 인가 URL을 돌려준다
   -> reauth=true는 로그인 시작과 같은 엔드포인트를 재사용하며 prompt=login만 켠다
2. 프론트가 그 URL로 카카오 로그인 화면을 다시 띄운다
   -> 기존 카카오 세션이 있어도 prompt=login이라 무조건 다시 로그인해야 한다
3. 카카오가 authorizationCode를 붙여 돌려준다
4. 프론트가 그 authorizationCode/state를 reason과 함께 여기(:withdraw)로 보낸다
5. 서버가 로그인 때와 완전히 같은 경로로 이 code를 검증해 새 id_token을 직접 카카오로부터
   받는다(클라이언트가 "재인증 성공했다"는 주장을 그대로 믿지 않는다)
6. 그 id_token의 sub가 지금 탈퇴하려는 회원의 것과 다르면 거부한다(다른 카카오 계정으로
   재로그인한 경우 — 본인 확인 실패)
```

통과하면 소프트 딜리트로 처리하고 카카오 연결 해제 API 를 호출한다. 주문 이력은 법정 기간 보존한다.

| 응답 | 코드 | 언제 |
|---|---|---|
| `204` | | 탈퇴 완료 |
| `401` | `AUTH-005` | 재인증한 카카오 계정이 본인 계정과 다르다 |
| `409` | `MEMBER-001` | 진행 중 주문이 있다 |
| `409` | `MEMBER-002` | 미완료 환불이 있다 |

**탈퇴해도 같은 카카오 계정으로 다시 가입할 수 있다.** 활성 회원만 유일성을 강제한다.

카카오 쪽에서 먼저 연결을 끊는 경우(회원이 카카오 계정 설정에서 직접 끊음)는 이 API로 안 들어오고
[웹훅](./auth.md#카카오-연결-해제-웹훅)으로 통보받는다 — 그때는 재인증을 요구할 수 없으니
(카카오가 이미 끊긴 상태) 이 절차를 거치지 않고 바로 탈퇴 처리한다.

## 배송지

### 목록

```
GET /v1/members/me/addresses
```

기본 배송지가 먼저 온다.

```json
{
  "addresses": [
    {
      "addressId": 3,
      "recipient": "홍*동",
      "phone": "010-****-5678",
      "zipcode": "06234",
      "roadAddress": "서울 강남구 테헤란로 1",
      "detailAddress": "10층",
      "isDefault": true
    }
  ]
}
```

### 등록, 수정, 삭제

```
POST   /v1/members/me/addresses
PATCH  /v1/members/me/addresses/{addressId}
DELETE /v1/members/me/addresses/{addressId}
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `recipient` | O | 50자 이하 |
| `phone` | O | 20자 이하 |
| `zipcode` | O | 10자 이하. 도로명 주소 API 결과 |
| `roadAddress` | O | 255자 이하 |
| `detailAddress` | | 255자 이하 |
| `isDefault` | | 기본값 `false` |

**기본 배송지는 회원당 하나다.** 새로 지정하면 이전 것이 자동으로 내려간다.
DB 가 계산 컬럼과 UNIQUE 로 이 규칙을 강제한다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `403` | `MEMBER-003` | 남의 배송지다 |
| `409` | `MEMBER-004` | 진행 중 주문이 참조하는 배송지는 지울 수 없다 |

## 관리자

### 회원 목록

```
GET /v1/admin/members?query={검색어}&status={상태}&gradeId={등급}
```

| 파라미터 | 설명 |
|---|---|
| `query` | 이름, 아이디, 연락처 부분 일치 |
| `status` | `PENDING_PROFILE`, `ACTIVE`, `BLOCKED`, `WITHDRAWN_FAILED`, `WITHDRAWN` |
| `gradeId` | 등급 |

가입일, 등급, 상태, 카카오 연동 일시를 함께 준다. **여기서도 개인정보는 마스킹한다.**

### 차단과 해제

```
POST /v1/admin/members/{memberId}:block
POST /v1/admin/members/{memberId}:unblock
```

```json
{ "reason": "부정 주문 반복" }
```

**차단하면 리프레시 토큰을 비운다.** 로그인, 주문, 글쓰기가 막힌다.

다만 **이미 나간 Access 토큰은 즉시 끊기지 않는다.** 무상태라 폐기할 수단이 없어
남은 수명(최대 30분) 동안 유효하다. 즉시 차단이 필요하면 블랙리스트가 있어야 한다.

| 응답 | 코드 | 언제 |
|---|---|---|
| `204` | | 처리 완료 |
| `409` | `MEMBER-005` | 이미 같은 상태다 |

### 등급 관리

```
GET    /v1/admin/member-grades
POST   /v1/admin/member-grades
PATCH  /v1/admin/member-grades/{gradeId}
DELETE /v1/admin/member-grades/{gradeId}
```

| 필드 | 필수 | 설명 |
|---|---|---|
| `name` | O | 등급명. 중복 불가 |
| `promotionRule` | | 승급 기준 서술 |
| `isDefault` | | 가입 시 부여할 등급 |

**기본 등급은 최대 하나다.** DB 가 강제한다. 다만 **최소 하나가 있어야 한다는 것은 DB 가 못 막아**
정합성 검사가 본다.

초기 등급은 브론즈, 실버, 골드다. 선착순 쿠폰 캠페인의 대상 등급과 연동된다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `409` | `MEMBER-006` | 소속 회원이 있는 등급은 지울 수 없다 |
| `409` | `MEMBER-007` | 등급명 중복 |

**관리자 계정 자체를 발급하고 비활성화하는 API 는 [admin.md](./admin.md) 에 있다.**
여기 있는 것은 관리자가 **회원 리소스**를 다루는 경로다.

## 정하지 못한 것

**등급별 할인율이 빠져 있다.** 요구사항은 등급마다 할인율을 두고 주문서에서 쓰라고 하는데,
`member_grade` 테이블에 할인율 컬럼이 없다. 지금은 등급명과 승급 기준만 다룰 수 있다.
