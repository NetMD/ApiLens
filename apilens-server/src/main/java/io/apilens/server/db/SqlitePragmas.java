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

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Single entry point for the three size-related SQLite PRAGMA reads.
 *
 * <p>// [Phase R23] R23/AC-07-3 — 같은 PRAGMA 문자열이 저장소에 <b>한 곳</b>으로 유지되게 하는 자리다.
 * // 이 클래스가 생기기 전에는 {@code MaintenanceController}(page_count · page_size)와
 * // {@code RetentionCleanupService}(freelist_count)가 각자 같은 모양의 private 메서드를 갖고 있었고,
 * // R23 이 화면(상태 응답)·야간 로그에서 같은 값을 더 읽게 되면서 네 번째·다섯 번째 사본이 생길 자리였다.
 *
 * <p><b>왜 bean 이 아니라 static 유틸인가</b>: {@code MaintenanceController} 가
 * {@code RetentionCleanupService} 를 주입받고 있어 반대 방향 주입은 순환 의존이 된다(기동 거부).
 * bean 으로 만들면 두 클래스의 생성자 소비처 10곳이 흔들린다. 두 클래스가 <b>이미 {@code JdbcTemplate} 을
 * 갖고 있으므로</b> 인자로 받는 static 유틸이면 생성자 변경 0 · 신규 bean 0 · 순환 위험 0 이다.
 * 프로젝트에 static 유틸 선례가 있다({@code RegexComplexityGuard}).
 *
 * <p><b>단위</b>: {@link #pageCount} 는 <b>개수</b>, {@link #pageSize} 는 <b>바이트/페이지</b>,
 * {@link #freelistCount} 는 <b>개수</b>다. <b>곱해야 바이트</b>가 된다 — 이 프로젝트에는 단위를 뭉개
 * 100배로 오독한 선례가 있어 소비처가 이름으로 단위를 밝힌다({@code dbSizeBytes} · {@code freePageBytes}).
 *
 * <p><b>범위 밖</b>: {@code StartupDbInitializer} 의 {@code PRAGMA auto_vacuum} 은 옮기지 않는다 —
 * 크기 관측이 아니라 <b>기동 시 1회 전환</b>이라 도메인이 다르다.
 *
 * <p>셋 다 매핑 불가(null) 시 <b>0</b> 을 돌려준다 — 옮겨 오기 전 세 메서드의 폴백 규칙 그대로다.
 */
public final class SqlitePragmas {

    private SqlitePragmas() {
    }

    /** {@code PRAGMA page_count} — DB 파일이 쓰는 페이지 <b>개수</b>. 매핑 불가 시 0. */
    public static long pageCount(JdbcTemplate jdbc) {
        Long v = jdbc.queryForObject("PRAGMA page_count", Long.class);
        return v == null ? 0L : v;
    }

    /** {@code PRAGMA page_size} — 페이지 한 장의 크기(<b>바이트/페이지</b>). 매핑 불가 시 0. */
    public static long pageSize(JdbcTemplate jdbc) {
        Long v = jdbc.queryForObject("PRAGMA page_size", Long.class);
        return v == null ? 0L : v;
    }

    /** {@code PRAGMA freelist_count} — 회수 가능한 빈 페이지 <b>개수</b>. 매핑 불가 시 0 (루프 미진입 = 안전한 방향). */
    public static long freelistCount(JdbcTemplate jdbc) {
        Long v = jdbc.queryForObject("PRAGMA freelist_count", Long.class);
        return v == null ? 0L : v;
    }
}
