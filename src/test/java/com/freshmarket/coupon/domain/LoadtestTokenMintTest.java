package com.freshmarket.coupon.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.freshmarket.common.auth.jwt.JwtTokenProvider;
import com.freshmarket.common.auth.jwt.TokenType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/*
 * 부하 시험용 토큰을 찍는 스크립트가 앱이 받아 주는 토큰을 만드는지 본다.
 *
 * 이 시험이 인증이 아니라 선착순 쿠폰 패키지에 있는 것은 부하 시험이 이 도메인의 일이어서다.
 * 씨딩 스크립트 시험도 같은 이유로 여기 있다.
 *
 * 그 스크립트는 파이썬이고 앱은 자바라, 서명이나 클레임 이름이 어긋나도 아무도 안 알려준다.
 * 시험 당일 2만 요청이 전부 401 로 돌아오고 나서야 알게 되고, 그러면 그 회차를 통째로 잃는다.
 */
class LoadtestTokenMintTest {

    // jjwt 의 Keys.hmacShaKeyFor 가 256비트 미만을 거부한다. 앱도 이 길이 아래로는 안 뜬다
    private static final String SECRET = "loadtest-secret-for-hs256-at-least-32-bytes";

    private static final long ACCESS_VALIDITY_MS = 1_800_000L;
    private static final long REFRESH_VALIDITY_MS = 1_209_600_000L;

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(SECRET, ACCESS_VALIDITY_MS, REFRESH_VALIDITY_MS);

    @Test
    void 스크립트가_찍은_토큰을_앱이_받아_준다(@TempDir Path 임시_디렉터리) throws Exception {
        Path 결과 = 임시_디렉터리.resolve("tokens.csv");
        assumeTrue(스크립트를_돌린다(결과), "python3 이 없어 건너뛴다");

        List<String> lines = Files.readAllLines(결과);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("memberId,token");

        String[] 첫_줄 = lines.get(1).split(",");
        String token = 첫_줄[1];

        // 앱이 실제로 보는 네 값이다. 하나라도 어긋나면 필터가 인증을 건너뛰어 401 이 된다
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
        assertThat(jwtTokenProvider.getId(token)).isEqualTo(Long.valueOf(첫_줄[0]));
        assertThat(jwtTokenProvider.getType(token)).isEqualTo(TokenType.MEMBER);
        assertThat(jwtTokenProvider.getRole(token)).isEqualTo("ROLE_USER");
    }

    /*
     * 시험은 두 장만 찍는다.
     * 서명과 클레임이 맞는지를 보는 것이라 개수는 볼 것이 없고, 2만 장을 매번 찍을 이유가 없다.
     */
    private boolean 스크립트를_돌린다(Path 결과) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("python3", "loadtest/mint-tokens.py");
        Map<String, String> env = builder.environment();
        env.put("JWT_SECRET", SECRET);
        env.put("TOKEN_COUNT", "2");
        env.put("TOKENS_OUT", 결과.toString());
        builder.redirectErrorStream(true);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return false;
        }
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return true;
    }
}
