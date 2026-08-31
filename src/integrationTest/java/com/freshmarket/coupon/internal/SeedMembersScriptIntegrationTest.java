package com.freshmarket.coupon.internal;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.freshmarket.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/*
 * 부하 시험용 회원 씨딩 스크립트를 실제 MySQL 에 태워 본다.
 *
 * 운영자가 시험 직전에 손으로 돌리는 파일이라, 거기서 처음 실패하면 그 회차를 통째로 잃는다.
 * 재귀 CTE 의 깊이 상한과 uk_member_active_provider, chk_member_withdrawn 이 걸리는 자리라
 * 눈으로 읽어서는 맞는지 알기 어렵다.
 */
@SpringBootTest
class SeedMembersScriptIntegrationTest extends IntegrationTestSupport {

    private static final String SCRIPT = "loadtest/seed-members.sql";
    private static final int EXPECTED = 20_000;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 스크립트가_회원을_다_넣고_다시_돌려도_같다() throws Exception {
        // when 두 번 돌린다. 시험 준비를 다시 하는 일이 흔하다
        run();
        run();

        // then
        assertThat(seeded()).isEqualTo(EXPECTED);
    }

    // 토큰을 찍는 쪽이 어느 id 로 만들지 알아야 하므로 id 가 정해진 범위에 있어야 한다
    @Test
    void 회원_id_가_정해진_범위에_들어온다() throws Exception {
        run();

        Long first = jdbcTemplate.queryForObject(
                "SELECT MIN(member_id) FROM member WHERE provider_user_id LIKE 'loadtest-%'", Long.class);
        Long last = jdbcTemplate.queryForObject(
                "SELECT MAX(member_id) FROM member WHERE provider_user_id LIKE 'loadtest-%'", Long.class);

        assertThat(first).isEqualTo(1_000_001L);
        assertThat(last).isEqualTo(1_000_000L + EXPECTED);
    }

    // 발급 행이 fk_mc_member 로 회원을 참조한다. 상태가 ACTIVE 여야 실제 발급 흐름과 같다
    @Test
    void 만든_회원이_전부_활성이다() throws Exception {
        run();

        Integer active = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'loadtest-%' AND status = 'ACTIVE'",
                Integer.class);

        assertThat(active).isEqualTo(EXPECTED);
    }

    private void run() throws Exception {
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(SCRIPT));
        }
    }

    private Integer seeded() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM member WHERE provider_user_id LIKE 'loadtest-%'", Integer.class);
    }
}
