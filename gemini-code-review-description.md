1. 트리거 (.github/workflows/gemini_review.yml): PR이 열리거나(opened), 새 커밋이 push되거나(synchronize), 재오픈(reopened)될 때 GitHub Actions가 실행됨.
2. 환경 준비: 레포를 전체 히스토리로 checkout(fetch-depth: 0)하고 Python 3.12 + google-genai, requests 설치. GEMINI_API_KEY가 secret으로 설정되어 있는지 먼저 확인.
3. 리뷰 스크립트 실행 (scripts/gemini_review.py):
  - git diff base...head로 PR의 변경분을 가져옴 (20만 자 넘으면 잘라냄).
  - code-review/CODEREVIEW.md와 그 하위 9개 가이드 문서(functional, effective-java, solid, unit-testing, api-design, database, jpa-rdb, security, concurrency)를 통째로 읽어 프롬프트에 삽입.
  - Gemini(gemini-2.5-flash)에게 "가이드라인 + diff"를 프롬프트로 던져 리뷰 생성 요청.
  - 응답을 GitHub PR에 이슈 코멘트로 자동 게시 (## 🤖 Gemini 자동 코드 리뷰).
4. 리뷰 기준 (code-review/CODEREVIEW.md가 정의):
  - 가이드 문서에 정의된 점검 항목만 지적하고, 근거(rationale) 문서는 설명 보강용으로만 사용.
  - 도메인형 패키지 구조(order, user 등)라 경로로 영역을 못 나누므로, diff의 내용 시그널(@Entity, @RestController, @Async 등)로 어떤 가이드를 적용할지 판단. 애매하면 적용하는 쪽으로 결정.
  - 같은 사안을 여러 가이드가 다룰 때는 "소유권 우선순위" 표에 따라 가장 전문적인 가이드 하나만 지적(중복 방지).
  - 지적 강도는 🔴[BLOCKER] / 🟠[MAJOR] / 🟡[MINOR] / ⚪[NIT] 4단계로 표기.
  - 모든 코멘트는 한국어로 작성(코드 식별자·경로 등은 원문 유지).

