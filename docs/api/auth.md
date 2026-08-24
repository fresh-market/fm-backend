# 인증

회원은 카카오 OIDC 로, 관리자는 자체 계정으로 로그인한다. **두 경로는 섞이지 않는다.**
공통 규약은 [README.md](./README.md) 에 있다.

| 구분 | 회원 | 관리자 |
|---|---|---|
| 수단 | 카카오 OIDC (인가 코드) | 아이디, 비밀번호 |
| 비밀번호 | 보관하지 않는다 | BCrypt 저장 |
| 계정 생성 | 최초 로그인 시 자동 | 최고관리자가 발급 |
| Access / Refresh | 30분 / 14일 | 30분 / 1일 |

카카오는 **로그인 시점의 신원 확인까지만** 쓴다. 이후 API 인증은 자체 JWT 로 하고 카카오 토큰은 보관하지 않는다.

## 경로

```
회원      POST   /v1/auth/tokens
          POST   /v1/auth/tokens:refresh
          DELETE /v1/auth/tokens
          GET    /v1/auth/kakao/authorize

관리자     POST   /v1/admin/auth/tokens
          POST   /v1/admin/auth/tokens:refresh
          DELETE /v1/admin/auth/tokens
          PUT    /v1/admin/auth/password
```

**리소스를 `tokens` 로 둔 것은 그것이 서버가 실제로 보관하는 것이기 때문이다.**

```sql
refresh_token_hash        CHAR(64)     -- SHA-256 hex. NULL 이면 로그아웃 상태다
refresh_token_expires_at  DATETIME(6)
```

세션 테이블은 없다. `DELETE /v1/auth/tokens` 는 위 두 컬럼을 비우는 일과 그대로 대응한다.
**세션을 리소스로 세웠다면 실재하지 않는 것에 이름을 붙이는 셈이 된다.**

**Access 토큰은 JWT 자체를 서버에 저장하지 않지만**, 로그아웃 시 발급 시각 기준의 무효화 정보를
별도로 기록해 기존 Access 토큰을 사용할 수 없게 할 수 있다.

`:refresh` 만 커스텀 메서드다. 갱신은 클라이언트가 필드를 고치는 것이 아니라
**서버가 규칙에 따라 수행하는 동작**이라 `PATCH` 로 표현되지 않는다 (`API-3-08`).

## 토큰을 어떻게 전달하나

**회원과 관리자 모두 Access/Refresh 토큰을 HttpOnly 쿠키로 전달한다.**
응답 본문에는 토큰 원문을 담지 않는다.

**회원은 둘 다 쿠키로 준다.**

| | 발급 (서버 -> 클라이언트) | 사용 (클라이언트 -> 서버) |
|---|---|---|
| Access | **`Set-Cookie` 헤더** | **브라우저가 쿠키로 자동 첨부** |
| Refresh | **`Set-Cookie` 헤더** | **브라우저가 쿠키로 자동 첨부** |

```
Set-Cookie: accessToken=...; HttpOnly; Secure; SameSite=Strict;
            Path=/; Max-Age=1800
Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict;
            Path=/v1/auth/; Max-Age=1209600
```

**(2026-08-18) 원래는 Access 를 응답 본문으로 주고 클라이언트가 `Authorization: Bearer` 헤더에
직접 실어 보내는 방식이었다.** 헤더 방식은 쿠키가 자동으로 안 붙어 CSRF 노출이 없다는 장점이
있었지만, 그 대신 스크립트가 토큰 값을 직접 들고 있어야 해서 XSS 로 뚫리면 토큰이 그대로
새 나갔다. **쿠키 방식이 더 적합하다고 보고 다시 이쪽으로 바꿨다** — `HttpOnly` 로 스크립트의
토큰 접근 자체를 막는 쪽을 우선했다. 아래 CSRF 단락은 이 결정에 맞춰 같이 고쳤다.

브라우저가 알아서 쿠키를 붙이므로 **스크립트가 값을 알 필요가 없고, 그래서 둘 다 `HttpOnly` 로
막을 수 있다.**

`Path` 가 다른 이유는 쓰임이 갈려서다. Access 는 인증이 필요한 모든 API 요청에 실려야 하니
`Path=/`. 회원 Refresh 는 로그인/재발급/로그아웃 경로를 포함하도록 `Path=/v1/auth/` 를 사용한다.
관리자 Refresh 는 `Path=/v1/admin/auth/` 를 사용한다.

**수명도 갈려서 `Max-Age` 가 다르다.** Access 는 30분이라 탈취돼도 구간이 짧지만, Refresh 는
14일이라 새면 2주 동안 재발급이 가능하다. 둘 다 `HttpOnly` 라 스크립트는 애초에 못 읽는다.

**대신 CSRF 노출 범위가 넓어졌다.** 브라우저가 쿠키를 자동으로 붙이기 때문이다. Access 가
`Path=/` 라 인증이 필요한 모든 API 가 이 노출을 받는다 — 헤더 방식이었을 땐 이 노출 자체가
없었다. `SameSite=Strict` 가 대부분을 막아 주지만 완전한 방어는 아니다. CSRF 토큰(더블서밋
쿠키 등) 도입 여부는 아직 정하지 못했다 — [정하지 못한 것](#정하지-못한-것) 참고. 회원 인증 체인은 현재 `SameSite=Strict` 를 사용하며 CSRF 토큰 방식은 아직 정하지 않았다.
관리자 인증 체인은 CSRF를 사용하되 로그인 요청은 CSRF 검사에서 제외한다. **이것이 XSS 위험과 맞바꾼 대가다.**

## 회원

### 로그인 시작

```
GET /v1/auth/kakao/authorize
```

서버가 `state` 와 `nonce` 를 만들어 저장(TTL 5분)하고 카카오 인가 URL 을 돌려준다.

```json
{ "authorizationUrl": "https://kauth.kakao.com/oauth/authorize?..." }
```

**`state` 는 CSRF 를, `nonce` 는 ID 토큰 재생 공격을 막는다.** 둘 다 카카오 권장 사항이다.

**콜백은 프론트가 받는다.** 카카오가 `redirect_uri` 로 `code` 를 붙여 302 로 되돌리면
프론트가 그 값을 아래 경로로 넘긴다. 이렇게 하면 **회원과 관리자의 발급 경로가 같은 모양이 되고**,
인증 수단이 늘어도(구글, 애플) 본문만 갈린다.

이 경로는 리소스 조작이 아니라 프로토콜 단계라 콜론 표기를 쓰지 않는다.

### 로그인

```
POST /v1/auth/tokens
```

```json
{ "authorizationCode": "...", "state": "...", "remember": false }
```

`remember` 는 선택 필드다(기본값 `false`). Refresh 토큰 자체의 서버 쪽 유효기간(14일)에는
영향이 없다 — 이 값은 오직 `refreshToken` 쿠키에 `Max-Age` 를 붙이느냐만 정한다.
`true` 면 브라우저를 껐다 켜도 쿠키가 남아 자동 로그인되고, `false` 면 세션 쿠키로 나가
브라우저 종료 시 삭제된다.

서버가 하는 일은 넷이다.

```
1. state 검증
2. 토큰 엔드포인트로 code 와 client_secret 전송
3. id_token 검증 (iss, aud, exp, nonce, JWKS 서명 RS256)
4. sub 로 회원 조회. 없으면 PENDING_PROFILE 상태로 생성
```

```json
{
  "code": "SUCCESS",
  "data": {
    "expiresInSeconds": 1800,
    "member": { "memberId": 1, "nickname": "홍길동", "status": "PENDING_PROFILE" }
  }
}
```

Access 도 Refresh 와 마찬가지로 본문에 없다. **둘 다 `Set-Cookie` 헤더로 나간다** (앞
[토큰을 어떻게 전달하나](#토큰을-어떻게-전달하나) 참고).

**`status` 가 `PENDING_PROFILE` 이면 추가 정보 입력이 남아 있다.** 프론트가 입력 폼으로 보낸다.

| 응답 | 코드 | 언제 |
|---|---|---|
| `201` | | 발급 성공 |
| `401` | `AUTH-001` | `state` 또는 `nonce` 불일치 |
| `401` | `AUTH-002` | `id_token` 검증 실패 |
| `503` | `AUTH-003` | 카카오 응답 없음. **재시도 가능하다** |

### 재발급

```
POST /v1/auth/tokens:refresh
```

**요청 본문이 없다.** 리프레시 토큰은 쿠키로 실려 온다.

**Rotation 을 적용한다.** Refresh 뿐 아니라 Access 도 이번 응답에서 같이 회전한다 — 둘 다 새
`Set-Cookie` 로 내린다. 재발급하면 이전 리프레시 토큰은 즉시 무효가 된다.
같은 토큰으로 두 번 오면 탈취를 의심해 그 회원의 리프레시 토큰을 비운다.

```json
{ "code": "SUCCESS", "data": { "expiresInSeconds": 1800, "member": null } }
```

응답 본문은 로그인과 같은 모양이되 `member` 는 항상 `null` 이다 — 재발급 시점엔 프론트가 이미
회원 정보를 들고 있어 다시 내려줄 필요가 없다.

| 오류 | 코드 | 언제 |
|---|---|---|
| `401` | `AUTH-004` | 만료되었거나 이미 사용된 토큰 |

### 로그아웃

```
DELETE /v1/auth/tokens
```

`refresh_token_hash` 를 비우고 **accessToken/refreshToken 쿠키를 둘 다 만료시킨다**(`Max-Age=0`).
그 뒤 카카오 로그아웃 API 를 어드민 키와 회원번호로 호출한다.
**카카오계정 함께 로그아웃은 제공하지 않는다.**

식별자를 받지 않는다. **지울 대상은 토큰의 주체로 정해진다** (`SEC-1-02`).
기기별 다중 로그인이 생기면 그때 `/v1/auth/tokens/{tokenId}` 가 의미를 갖는다.
지금은 컬럼이 하나라 기기 한 대만 유지된다.

| 응답 | |
|---|---|
| `204` | 본문 없음 |
| `401` | 로그인 상태가 아니다 |

### 카카오 연결 해제 웹훅

```
GET  /webhook/kakao/unlink
POST /webhook/kakao/unlink
```

회원이 우리 서비스가 아니라 **카카오 계정 설정에서 직접 연결을 끊었을 때** 카카오가 호출한다.
`/v1/...` 접두사가 없는 이유는 회원이나 관리자 같은 우리 쪽 소비자가 호출하는 경로가 아니라
카카오가 서버 대 서버로 찌르는 콜백이기 때문이다 — 인증도 우리 JWT 체계가 아니라
`Authorization: KakaoAK <admin-key>` 로 카카오가 직접 검증한다 (`SecurityConfig`의
공개 경로 목록에도 그래서 따로 올라가 있다).

| 파라미터 | 위치 | 설명 |
|---|---|---|
| `app_id` | 쿼리 | 우리 카카오 앱 ID와 일치해야 한다 |
| `user_id` | 쿼리 | 연결이 끊긴 카카오 사용자(`sub`) ID |

**`app_id` 나 `Authorization` 이 안 맞으면 조용히 무시하고 `200` 을 돌려준다.** 카카오 웹훅
스펙상 비정상 요청에도 재시도를 유발하면 안 되므로 실패를 응답 코드로 드러내지 않는다 — 대신
로그로 남긴다.

일치하면 해당 회원을 탈퇴 처리(`WITHDRAWN`)하고 리프레시 토큰을 비운다. **카카오 unlink API를
다시 호출하지는 않는다** — 이미 카카오 쪽에서 끊긴 상태라 호출하면 중복이다. 회원이 없거나
이미 탈퇴 상태여도 예외를 던지지 않는다(마찬가지로 항상 `200`).

| 응답 | |
|---|---|
| `200` | 항상. 처리 성공/실패, 심지어 인증 실패와도 무관하다 |

## 관리자

### 로그인

```
POST /v1/admin/auth/tokens
```

```json
{ "loginId": "admin.kim", "password": "..." }
```

| 필드 | 필수 | 제약 |
|---|---|---|
| `loginId` | O | 50자 이하 |
| `password` | O | **72자 이하.** BCrypt 가 그 이상을 조용히 잘라낸다 |

```json
{
  "code": "SUCCESS",
  "data": {
    "expiresInSeconds": 1800,
    "admin": { "loginId": "admin.kim", "name": "김관리", "role": "ADMIN" }
  }
}
```

| 응답 | 코드 | 언제                                             |
|---|---|------------------------------------------------|
| `201` | | 발급 성공                                          |
| `401` | `ADMIN-001` | 아이디 또는 비밀번호 불일치 또는 비활성 계정. **사유를 구분해 알리지 않는다** |

**실패 응답이 계정 존재 여부를 구분해 주지 않는다** (`SEC-6-04`). 메시지뿐 아니라 **응답 시간도 맞춘다.**
계정이 없을 때도 더미 해시로 BCrypt 를 돌려, 시간 차이로 아이디 존재가 드러나지 않게 한다.

### 재발급과 로그아웃

```
POST   /v1/admin/auth/tokens:refresh
DELETE /v1/admin/auth/tokens
```

**관리자 토큰 재발급은 이번 관리자 로그아웃 구현 범위에 포함하지 않는다.**

관리자 로그아웃 시 현재 관리자 계정의 Refresh Token을 폐기하고, 로그아웃 이전에 발급된 Access Token도 즉시 사용할 수 없도록 처리한다.

로그아웃 대상은 별도의 관리자 ID를 요청받지 않고 현재 인증된 Access Token의 주체를 기준으로 결정한다.

로그아웃 시 `accessToken`, `refreshToken` 쿠키를 모두 `Max-Age=0`으로 만료한다.
관리자 Refresh Token 쿠키는 발급 시와 동일한 `Path=/v1/admin/auth/`를 사용한다.

정상 처리 시 `204 No Content`를 반환한다.

```
Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict;
            Path=/v1/admin/auth/; Max-Age=86400
```

Refresh 가 1일인 것은 **자동 로그인을 제공하지 않기 때문이다.** 관리자 콘솔은 회원 서비스보다
권한이 크므로 로그인 상태를 오래 끌지 않는다.

`Path` 가 달라 **관리자 쿠키가 회원 API 요청에 실려 가지 않는다.**

**요구사항이 `HttpOnly` 쿠키를 지정한 것은 회원뿐이다.** 관리자는 전달 방식을 적어 두지 않았고,
여기서 같은 방식으로 정했다. 근거는 보안이다.

```
관리자 콘솔도 브라우저 앱이라 XSS 위험이 같다
관리자 토큰이 더 값지다. 털리면 상품 삭제, 환불, 권한 변경까지 열린다
수명 1일은 위험을 줄일 뿐 없애지 못한다
```

**전제가 하나 있다. 관리자 콘솔과 API 가 같은 사이트여야 한다.**
다른 사이트면 `SameSite=Strict` 쿠키가 실리지 않아 `None` 으로 낮춰야 하는데,
그러면 CSRF 방어가 사라져 쿠키를 쓴 이점이 반감된다.

배포를 나눠야 하는 상황이 오면 **`SameSite` 를 낮추기 전에 같은 사이트로 묶는 방법을 먼저 찾는다.**
서브도메인은 같은 사이트로 취급되므로 `admin.example.com` 과 `api.example.com` 은 문제없다.

### 비밀번호 변경

```
PUT /v1/admin/auth/password
```

**관리자 비밀번호 변경은 현재 관리자 로그인 구현 범위에 포함하지 않는다.**

```json
{ "currentPassword": "...", "newPassword": "..." }
```

싱글톤 하위 리소스다 (`API-2-07`). 비밀번호는 회원당 하나뿐이라 식별자가 없고,
**교체이므로 `PUT` 이 맞다.**

**변경하면 그 계정의 리프레시 토큰을 비운다.** 임시 비밀번호 계정은 첫 로그인 시 변경이 강제된다.

| 응답 | 코드 | 언제 |
|---|---|---|
| `204` | | 변경 성공 |
| `401` | `ADMIN-003` | 현재 비밀번호 불일치 |
| `422` | `ADMIN-004` | 정책 미충족. 영문 대소문자, 숫자, 특수문자 조합 10자 이상 |

**회원에게는 이 경로가 없다.** 비밀번호를 보관하지 않고 카카오가 관리한다.

## 정하지 못한 것

**5회 실패 시 30분 잠금은 현재 프로젝트 구현 범위에서 제외한다.**
요구사항에는 있으나 `admin` 테이블에 실패 횟수와 잠금 시각 컬럼이 없으며, 현재 구현에서는 관련 컬럼과 잠금 응답을 추가하지 않는다.

**관리자 전용 Rate Limit도 현재 프로젝트 구현 범위에서 제외한다.** 계정 단위 잠금과 별개로 IP 단위 제한이 필요한데,
애플리케이션과 앞단(ALB, WAF) 중 어디서 할지 결정되지 않았다.

**회원 Access 토큰의 CSRF 토큰 방어도 정해지지 않았다.** (2026-08-18) Access 를 헤더에서
쿠키로 되돌리면서(위 [토큰을 어떻게 전달하나](#토큰을-어떻게-전달하나) 참고) 인증이 필요한
모든 회원 API가 CSRF 노출을 받게 됐다. 지금은 `SameSite=Strict` 만으로 버티고 `csrf(disable)`
상태다 — 더블서밋 쿠키 같은 CSRF 토큰 메커니즘을 넣을지는 아직 결정하지 않았다.
