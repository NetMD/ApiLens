/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apilens.server.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R12] T-A2 — datasource URL PRAGMA 적용 + StartupDbInitializer 멱등 (Design §7.2).
 *
 * <p>// [Phase R12] AC-A2-1 — V-06 가드: PRAGMA 는 URL 쿼리 파라미터로 적용되는데, URL 오타는
 * // **silent 실패** (sqlite-jdbc 가 모르는 파라미터를 조용히 무시) — 본 테스트가 실제
 * // application.yml 의 URL 문자열을 그대로 읽어 연결 후 PRAGMA 결과값으로 봉인한다.
 * <p>// [Phase R12] AC-A2-2 — D-04 비협상: "파이프라인이 운영 DB 파일을 추가로 삭제하지 말 것"
 * // (사용자 명시 비협상 결정) — 본 테스트의 모든 DB 는 @TempDir 신규 파일. 운영 apilens.db 무접점.
 */
class DbPragmaTest {

    private static final Pattern YML_URL = Pattern.compile("url:\\s*(jdbc:sqlite:\\S+)");

    @TempDir
    Path tempDir;

    // ─── V-06: application.yml URL 실측 봉인 (오타 = 즉시 검출) ─────────────

    /**
     * ⚠️ 실측 ground truth (V-06 이 잡아낸 silent 실패): sqlite-jdbc 3.47.1 은
     * {@code auto_vacuum} 을 URL pragma 로 지원하지 않는다 ({@code SQLiteConfig.Pragma} 에
     * AUTO_VACUUM 부재 — silent drop). 따라서 URL 봉인 대상은 지원되는 3종이며,
     * auto_vacuum=INCREMENTAL(2) 은 {@link StartupDbInitializer} 의 동일 connection
     * PRAGMA+VACUUM 경로로 봉인한다 (신규/기존 DB 공통).
     */
    @Test
    void appliesThreeUrlPragmasAndConvertsAutoVacuumViaInitializer() throws Exception {
        String url = readDatasourceUrlFromYml();
        // 운영 파일명(apilens.db)을 temp 파일로 치환 — 쿼리 파라미터 문자열은 yml 원문 그대로 보존
        Path dbFile = tempDir.resolve("apilens-pragma-test.db");
        String testUrl = url.replace("jdbc:sqlite:apilens.db", "jdbc:sqlite:" + dbFile.toAbsolutePath());
        assertFalse(testUrl.equals(url), "yml URL 에서 운영 파일명 치환 실패 — URL 형식 변경 여부 확인: " + url);
        assertFalse(url.contains("auto_vacuum"),
                "auto_vacuum 은 sqlite-jdbc URL pragma 미지원 (silent drop) — URL 재유입 금지, "
                        + "전환은 StartupDbInitializer 전담");

        try (Connection con = DriverManager.getConnection(testUrl);
             Statement st = con.createStatement()) {
            assertEquals("wal", queryString(st, "PRAGMA journal_mode"),
                    "journal_mode=WAL 미적용 — URL 파라미터 오타 의심 (V-06)");
            assertEquals(5000, queryInt(st, "PRAGMA busy_timeout"),
                    "busy_timeout=5000 미적용 (V-06)");
            assertEquals(1, queryInt(st, "PRAGMA synchronous"),
                    "synchronous=NORMAL(1) 미적용 (V-06)");
        }

        // auto_vacuum: 신규 생성 DB 도 첫 기동의 StartupDbInitializer 가 INCREMENTAL 로 전환
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(testUrl);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        new StartupDbInitializer(jdbc).initialize();
        assertEquals(StartupDbInitializer.AUTO_VACUUM_INCREMENTAL,
                jdbc.queryForObject("PRAGMA auto_vacuum", Integer.class),
                "auto_vacuum=INCREMENTAL(2) — initializer 경로 봉인 (V-06)");
    }

    // ─── AC-A2-2: StartupDbInitializer 멱등 (2회 실행 시 VACUUM 1회) ─────────

    @Test
    void convertsExistingDbOnceAndSkipsOnSecondRun() throws Exception {
        // v0.1 사용자 DB 재현: PRAGMA 없는 평 URL 로 생성 → auto_vacuum=0(NONE) 으로 굳음
        Path dbFile = tempDir.resolve("apilens-vacuum-test.db");
        SQLiteDataSource plain = new SQLiteDataSource();
        plain.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        Flyway.configure().dataSource(plain).locations("classpath:db/migration").load().migrate();

        // 운영 경로 재현: 지원되는 3 pragma URL 로 재접속 — auto_vacuum 전환은 initializer 가
        // 동일 connection 에서 PRAGMA+VACUUM 수행 (connection 풀 동작과 무관하게 결정적)
        SQLiteDataSource pragmaDs = new SQLiteDataSource();
        pragmaDs.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath()
                + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000");
        JdbcTemplate jdbc = new JdbcTemplate(pragmaDs);
        StartupDbInitializer initializer = new StartupDbInitializer(jdbc);

        boolean firstRun = initializer.initialize();
        assertTrue(firstRun, "기존 DB(auto_vacuum=NONE) 첫 기동 — 1회 VACUUM 전환이 실행돼야 한다");
        Integer converted = jdbc.queryForObject("PRAGMA auto_vacuum", Integer.class);
        assertNotNull(converted);
        assertEquals(StartupDbInitializer.AUTO_VACUUM_INCREMENTAL, converted.intValue(),
                "VACUUM 후 auto_vacuum=INCREMENTAL(2) 이 파일에 반영돼야 한다");

        boolean secondRun = initializer.initialize();
        assertFalse(secondRun, "멱등 가드: 두 번째 실행은 VACUUM 을 skip 해야 한다 (auto_vacuum 값 = 적용 완료 마커)");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    /** 테스트 classpath 의 main 리소스 application.yml 에서 datasource URL 원문 추출. */
    private static String readDatasourceUrlFromYml() throws Exception {
        try (InputStream in = DbPragmaTest.class.getResourceAsStream("/application.yml")) {
            assertNotNull(in, "classpath:/application.yml 미존재 — 리소스 구성 확인");
            String yml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = YML_URL.matcher(yml);
            assertTrue(m.find(), "application.yml 에서 jdbc:sqlite URL 을 찾지 못함");
            return m.group(1);
        }
    }

    private static String queryString(Statement st, String pragma) throws Exception {
        try (ResultSet rs = st.executeQuery(pragma)) {
            assertTrue(rs.next(), pragma + " 결과 없음");
            return rs.getString(1).toLowerCase();
        }
    }

    private static int queryInt(Statement st, String pragma) throws Exception {
        try (ResultSet rs = st.executeQuery(pragma)) {
            assertTrue(rs.next(), pragma + " 결과 없음");
            return rs.getInt(1);
        }
    }
}
