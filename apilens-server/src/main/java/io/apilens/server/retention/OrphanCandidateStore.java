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
package io.apilens.server.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Single access point for the orphan-span candidate list of the two-night sweep.
 *
 * <p>// [Phase R22] R22/AC-03-5/R22/AC-03-6/R22/AC-03-12 — R22/AC-03-5 verbatim: "후보 목록은
 * // {@code settings} 테이블의 내부 키 {@link #KEY_ORPHAN_CANDIDATES} 에 저장한다. <b>마이그레이션 0</b>
 * // — settings 는 V3 에서 만들어진 범용 키-값 저장소다". 사용자 명시 비협상 결정(NFR-02 마이그레이션 0).
 * // CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용 — 스키마 변경 0 · 새 {@code V6__*.sql} 0.
 *
 * <p><b>왜 별도 클래스인가</b> (Design §2.3-(b) — 단일 위임 진입점):
 * <ul>
 *   <li>키 문자열·직렬화·개수 상한·파싱 방어가 <b>한 곳</b>에 모인다. 키 문자열 리터럴이 production 에
 *       <b>딱 1곳</b>({@link #KEY_ORPHAN_CANDIDATES} 선언)만 존재하게 되어 grep 으로 검증된다.</li>
 *   <li>{@code SettingsService} 에 넣지 않는다 — 그쪽은 <b>설정 화면 조회 경로</b>라 거기서 예외가 나면
 *       설정 화면 전체가 안 뜬다. 패키지도 달라 package-private 로 막을 수 없다.</li>
 *   <li>Spring bean 이 <b>아니다</b>. bean 주입은 {@link RetentionCleanupService} 생성자를 5-인자로
 *       만들어 server 테스트 5곳의 호출부를 전부 깨뜨린다 (진입점 시그니처 불변 봉인).</li>
 * </ul>
 *
 * <p>★ <b>이 키는 {@code SettingsRegistry.ALLOWED_KEYS} 에 넣지 않는다</b> — 사용자에게 노출되지 않는
 * 내부 상태다. 허용 키를 넓히면 사용자가 {@code PUT /v1/settings} 로 내부 상태를 덮어쓸 수 있게 된다
 * (NFR-09 diff 0 — 사용자 명시 비협상 결정).
 *
 * <p><b>값의 형식</b>: 쉼표로 이은 span_id 문자열. span_id 는 W3C 규격 16진수라 쉼표가 들어갈 수 없으므로
 * 저장·파싱이 외부 직렬화 라이브러리 없이 끝난다. 빈 목록은 {@code ''} (빈 문자열) —
 * {@code settings.value} 가 {@code TEXT NOT NULL} 이라 NULL 로 쓰지 않는다.
 */
final class OrphanCandidateStore {

    private static final Logger log = LoggerFactory.getLogger(OrphanCandidateStore.class);

    /**
     * ③ 후보 목록 저장 키 — <b>내부 전용</b>. {@code SettingsRegistry.ALLOWED_KEYS} 에 넣지 않는다
     * (NFR-09 — 사용자 명시 비협상 결정). 이 리터럴은 production 에 이 한 곳만 존재한다 (G-06).
     */
    static final String KEY_ORPHAN_CANDIDATES = "retention.orphanCandidates";

    /**
     * 조각 하나의 길이 상한 — span_id 는 W3C 규격 16자리 16진수다. 64 는 넉넉한 상계로,
     * 수동 DB 편집 등으로 비정상 값이 들어와도 행이 비대해지지 않게 막는다.
     */
    private static final int MAX_ID_LENGTH = 64;

    private final JdbcTemplate jdbc;

    /** ★생성자에서 jdbc 를 호출하지 않는다 — 호출처의 단위 테스트가 mock {@code JdbcTemplate} 을 넘긴다. */
    OrphanCandidateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 어젯밤 후보 목록.
     *
     * <p>// [Phase R22] R22/AC-03-12 verbatim: "후보 값이 깨져 있어도 정리 시각 갱신은 성공한다.
     * // 후보 파싱 실패는 {@code SettingsService} 의 방어 관용구와 같은 모양으로 흡수한다 — 예외를
     * // 밖으로 던지지 않는다". 어떤 입력에도 예외를 던지지 않고 빈 목록으로 닫는다.
     *
     * <p>읽기 방어 4단: 쉼표 분해 → 빈 조각 제거 → 길이 {@value #MAX_ID_LENGTH} 초과 조각 제거 →
     * 개수 {@code ORPHAN_CANDIDATE_CAP} 로 자름.
     */
    List<String> read() {
        String raw;
        try {
            raw = jdbc.query("SELECT value FROM settings WHERE key = ?",
                            (rs, rowNum) -> rs.getString(1), KEY_ORPHAN_CANDIDATES)
                    .stream().findFirst().orElse(null);
        } catch (Exception e) {
            // 조회 자체가 실패해도 밖으로 던지지 않는다 — 그 밤의 삭제가 0 이 될 뿐이다 (안전한 방향).
            log.warn("orphan candidate read failed — treating as empty (no deletion tonight)", e);
            return List.of();
        }
        if (raw == null || raw.isBlank()) {
            // 행 자체가 없거나 빈 목록 — 첫 실행의 정상 상태다 (경고 아님).
            return List.of();
        }
        List<String> parsed = new ArrayList<>();
        int dropped = 0;
        for (String piece : raw.split(",", -1)) {
            String id = piece.trim();
            if (id.isEmpty()) {
                continue;                       // "a,,b," 같은 빈 조각은 조용히 버린다.
            }
            if (id.length() > MAX_ID_LENGTH) {
                dropped++;                      // 비정상 길이 — 세어서 경고만 하고 버린다.
                continue;
            }
            parsed.add(id);
            if (parsed.size() >= RetentionCleanupService.ORPHAN_CANDIDATE_CAP) {
                break;                          // 상한까지만 읽는다 (쓰기 상한과 같은 값).
            }
        }
        if (dropped > 0) {
            // SettingsService 의 "invalid stored settings value" 와 같은 결의 방어 로그.
            log.warn("invalid stored orphan candidate entries dropped: count={} — no deletion for them tonight",
                    dropped);
        }
        return parsed;
    }

    /**
     * 오늘밤 후보로 덮어쓴다 — 같은 트랜잭션 안에서 불린다 (R22/AC-03-11).
     *
     * <p>{@code SettingsService} 의 {@code ON CONFLICT(key) DO UPDATE SET value = excluded.value,
     * updated_at = excluded.updated_at} 관용구를 <b>그대로</b> 따른다 (새 관용구 0).
     * {@code settings.key} 가 PRIMARY KEY 라 이 문장은 <b>이미 멱등</b>이다 — 같은 밤에 두 번 돌아도
     * 결과가 같으므로 delete-then-insert 보강이 필요 없다.
     *
     * <p>★ {@code updated_at} 은 서버 시각이 자동 기록되지만 <b>삭제 판정에 쓰지 않는다</b>
     * (R22/AC-03-9 — 시각 비교 금지. "유예 10분" 류 상수를 코드에 남기지 않는다).
     *
     * <p><b>쓰기 방어</b>: 쉼표가 든 id 는 목록에서 버리고 경고한다 — 값이 섞여 두 id 가 하나로 합쳐지는
     * 경로를 원천 차단한다.
     */
    void write(List<String> spanIds) {
        List<String> safe = new ArrayList<>();
        int rejected = 0;
        for (String id : spanIds) {
            if (id == null || id.isBlank() || id.indexOf(',') >= 0 || id.length() > MAX_ID_LENGTH) {
                rejected++;
                continue;
            }
            safe.add(id);
            if (safe.size() >= RetentionCleanupService.ORPHAN_CANDIDATE_CAP) {
                break;
            }
        }
        if (rejected > 0) {
            log.warn("orphan candidate entries rejected before write: count={} (comma or length violation)",
                    rejected);
        }
        // 빈 목록은 '' 로 저장한다 — settings.value 가 TEXT NOT NULL 이라 NULL 을 쓸 수 없다.
        String value = String.join(",", safe);
        jdbc.update("""
                        INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
                        ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                        """,
                KEY_ORPHAN_CANDIDATES, value, System.currentTimeMillis());
    }
}
