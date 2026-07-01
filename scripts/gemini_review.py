import os
import subprocess
import requests
from pathlib import Path
from google import genai

GUIDE_DIR = Path("code-review")
MAIN_GUIDE = "CODEREVIEW.md"
GUIDE_FILES = [
    "functional.md",
    "effective-java-guideline.md",
    "solid-principles.md",
    "unit-testing-guideline.md",
    "api-design-guideline.md",
    "database.md",
    "jpa-rdb-guideline.md",
    "security.md",
    "concurrency.md",
]
MAX_DIFF_CHARS = 200_000


def load_guidelines() -> str:
    sections = []
    main = GUIDE_DIR / MAIN_GUIDE
    if main.exists():
        sections.append(main.read_text(encoding="utf-8"))
    for name in GUIDE_FILES:
        path = GUIDE_DIR / name
        if path.exists():
            sections.append(f"### {name}\n\n{path.read_text(encoding='utf-8')}")
    return "\n\n---\n\n".join(sections)


def get_diff() -> str:
    base = os.environ["BASE_SHA"]
    head = os.environ["HEAD_SHA"]
    result = subprocess.run(
        ["git", "diff", f"{base}...{head}"],
        capture_output=True, text=True, check=True,
    )
    diff = result.stdout
    if len(diff) > MAX_DIFF_CHARS:
        diff = diff[:MAX_DIFF_CHARS] + "\n\n... (diff truncated)"
    return diff


def build_prompt(guidelines: str, diff: str) -> str:
    return f"""당신은 Java/Spring 백엔드 시니어 개발자입니다. 아래 코드 리뷰 가이드라인을 정확히 따라 PR diff를 리뷰하세요.

## 코드 리뷰 가이드라인

{guidelines}

## PR Diff

```diff
{diff}
```

위 가이드라인의 규칙과 출력 형식(CODEREVIEW.md 예시 참고)에 맞춰 리뷰를 작성하세요.
- 각 코멘트에 `[BLOCKER]` / `[MAJOR]` / `[MINOR]` / `[NIT]` 접두어를 붙이세요.
- 지적이 없으면 "지적 사항 없음"이라고 간결하게 작성하세요.
- 모든 설명은 한국어로 작성하되, 코드 식별자·파일 경로·애너테이션은 원문을 유지하세요.
"""


def post_comment(body: str) -> None:
    token = os.environ["GITHUB_TOKEN"]
    repo = os.environ["REPO"]
    pr_number = os.environ["PR_NUMBER"]

    url = f"https://api.github.com/repos/{repo}/issues/{pr_number}/comments"
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    resp = requests.post(url, json={"body": body}, headers=headers)
    resp.raise_for_status()
    print(f"Posted: {resp.json()['html_url']}")


def main() -> None:
    client = genai.Client(api_key=os.environ["GEMINI_API_KEY"])

    diff = get_diff()
    if not diff.strip():
        print("No diff found, skipping review.")
        return

    guidelines = load_guidelines()
    prompt = build_prompt(guidelines, diff)

    response = client.models.generate_content(
        model="gemini-2.5-flash",
        contents=prompt,
    )

    review = response.text
    comment = f"## 🤖 Gemini 자동 코드 리뷰\n\n{review}"
    post_comment(comment)


if __name__ == "__main__":
    main()
