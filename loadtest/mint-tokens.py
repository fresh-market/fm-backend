#!/usr/bin/env python3
"""부하 시험용 액세스 토큰을 미리 찍는다.

AuthRateLimitFilter 가 POST /v1/auth/tokens 를 IP 당 분당 10회로 막는다. 2만 명이 로그인부터
하면 그 필터에서 끝나고, 필터를 시험용으로 끄면 정작 시험하려는 발급 경로가 아닌 것을 잰다.
그래서 로그인을 건너뛰고 같은 서명 키로 토큰을 직접 만든다.

토큰의 모양은 JwtTokenProvider.createAccessToken 과 같아야 한다.

    sub    회원 id (문자열)
    type   MEMBER 또는 ADMIN
    role   ROLE_USER 또는 ROLE_ADMIN
    iat    발급 시각
    exp    만료 시각

실행:

    JWT_SECRET=<앱과 같은 값> python3 loadtest/mint-tokens.py

결과는 loadtest/tokens.csv 다. 첫 줄이 헤더이고 k6 가 그대로 읽는다.
개수와 출력 경로는 TOKEN_COUNT 와 TOKENS_OUT 으로 바꾼다.
"""

import base64
import csv
import hashlib
import hmac
import json
import os
import sys
import time

# seed-members.sql 이 만드는 회원 id 범위와 같아야 한다
ID_BASE = 1_000_000

# 시험이 길어져도 중간에 만료되지 않게 넉넉히 잡는다. 앱 기본값은 30분이다
VALIDITY_SECONDS = 6 * 60 * 60

DEFAULT_OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tokens.csv")

# 개수와 출력 경로를 환경 변수로 받는다. 시험이 두 장만 찍어 서명이 맞는지 확인할 때 쓴다
COUNT = int(os.environ.get("TOKEN_COUNT", 20_000))
OUT_PATH = os.environ.get("TOKENS_OUT", DEFAULT_OUT)


def b64(raw: bytes) -> str:
    """JWT 는 패딩 없는 base64url 을 쓴다."""
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode("ascii")


def mint(secret: bytes, subject: int, token_type: str, role: str, now: int) -> str:
    header = {"alg": "HS256"}
    payload = {
        "sub": str(subject),
        "type": token_type,
        "role": role,
        "iat": now,
        "exp": now + VALIDITY_SECONDS,
    }
    # 공백 없이 직렬화한다. 서명은 바이트 그대로를 덮으므로 여기서 한 글자만 달라도 검증이 깨진다
    signing_input = "{}.{}".format(
        b64(json.dumps(header, separators=(",", ":")).encode()),
        b64(json.dumps(payload, separators=(",", ":")).encode()),
    )
    signature = hmac.new(secret, signing_input.encode("ascii"), hashlib.sha256).digest()
    return "{}.{}".format(signing_input, b64(signature))


def main() -> int:
    secret = os.environ.get("JWT_SECRET")
    if not secret:
        print("JWT_SECRET 이 없다. 앱이 쓰는 값과 같아야 한다.", file=sys.stderr)
        return 1
    # HS256 은 키가 256비트 이상이어야 한다. jjwt 의 Keys.hmacShaKeyFor 가 짧으면 거부한다
    if len(secret.encode()) < 32:
        print("JWT_SECRET 이 32바이트보다 짧다. 앱도 이 키로는 못 뜬다.", file=sys.stderr)
        return 1

    key = secret.encode()
    now = int(time.time())

    with open(OUT_PATH, "w", newline="") as f:
        # 줄 끝을 LF 로 못 박는다. csv 기본값이 CRLF 라, 그대로 두면 토큰 뒤에 \r 이 붙어
        # Authorization 헤더가 망가지고 톰캣이 400 으로 거절한다
        writer = csv.writer(f, lineterminator="\n")
        writer.writerow(["memberId", "token"])
        for n in range(1, COUNT + 1):
            member_id = ID_BASE + n
            writer.writerow([member_id, mint(key, member_id, "MEMBER", "ROLE_USER", now)])

    # 이벤트를 여는 관리자 토큰은 한 장이면 된다. 표에 섞지 않고 화면으로 낸다
    print("찍은 회원 토큰: {}개 -> {}".format(COUNT, OUT_PATH))
    print("관리자 토큰:")
    print(mint(key, 1, "ADMIN", "ROLE_ADMIN", now))
    return 0


if __name__ == "__main__":
    sys.exit(main())
