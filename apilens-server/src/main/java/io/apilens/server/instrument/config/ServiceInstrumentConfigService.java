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
package io.apilens.server.instrument.config;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores/loads the per-service desired instrument config ({@code service_instrument_configs}, V5).
 *
 * <p>[Phase R20] R20/AC-03-1 — 저장 위치 = 신규 테이블(OQ-4 architect 확정). 행 부재 = config 미설정
 * = 202 응답 {@code instrumentConfig} 필드 생략(부재 허용형과 1:1). 각 컬럼 NULL = 그 축 지시 없음.
 *
 * <p>쓰기 = PK upsert 1문(멱등 — {@code ON CONFLICT(service_name) DO UPDATE}). 읽기 = PK 단건
 * SELECT(캐시 없음 — WAL 다중 reader 라 writer 비차단, 캐시 무효화 버그 위험 &gt; SELECT 비용).
 * 집계 쿼리 아님 — traces 기점 규약(불변식 12)의 적용 대상 밖(신규 집계 쿼리 0건, NFR-03).
 * 전건 파라미터 바인딩({@code ?}) — 문자열 연결 0, LIKE 미사용(정확 일치 PK 조회만).
 *
 * <p>[Phase R20] R20/AC-03-5 — services 테이블에 없는 서비스명도 허용(재배포 전 선설정 운영 동선 —
 * config 는 지시이지 상태가 아님). 입력 상한(무키 폴백 환경 방어): serviceName ≤ 200자 ·
 * gateExcludes ≤ 100개 × 항목당 ≤ 512자 · 공백 항목 제거 — 초과 시 400(IllegalArgumentException).
 */
@Service
public class ServiceInstrumentConfigService {

    /** [Phase R20] R20/AC-03-5 — 입력 상한 상수(매직 넘버 상수화). */
    static final int MAX_SERVICE_NAME_LENGTH = 200;
    static final int MAX_GATE_EXCLUDES_COUNT = 100;
    static final int MAX_GATE_EXCLUDE_ITEM_LENGTH = 512;

    private final JdbcTemplate jdbc;

    public ServiceInstrumentConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** PK 단건 SELECT — 행 부재면 {@link Optional#empty()} (= 202 필드 생략·GET 404). */
    public Optional<InstrumentConfigPayload> find(String serviceName) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                        SELECT capture_params, capture_result_set, require_entry_root, gate_excludes
                        FROM service_instrument_configs
                        WHERE service_name = ?
                        """,
                serviceName);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        return Optional.of(new InstrumentConfigPayload(
                toBoolean(row.get("capture_params")),
                toBoolean(row.get("capture_result_set")),
                toBoolean(row.get("require_entry_root")),
                splitGateExcludes((String) row.get("gate_excludes"))));
    }

    /**
     * 전체 교체 저장(멱등 — PK upsert 1문). 저장된 config echo 를 반환한다.
     *
     * <p>[Phase R20] R20/AC-03-2 — 화면 없음(Q-U6), curl 검증 전제. "게이트 exclude 추가"(Q-U4 어휘)는
     * 운영자 행위 관점 서술이고 구현은 전체 교체가 안전 단순 — 기동값 = 빈 목록 = 최대 계측이라
     * 어떤 목록이든 "기동 {@code -D} 값 이하"(reduce-only 안, Q-U5).
     */
    public InstrumentConfigPayload put(String serviceName, InstrumentConfigPayload request) {
        validateServiceName(serviceName);
        List<String> gateExcludes = validateAndNormalizeGateExcludes(
                request == null ? null : request.gateExcludes());
        if (gateExcludes != null && gateExcludes.isEmpty()) {
            // 빈 목록 지시 = "목록 지시 없음"으로 정규화 — PUT echo 와 후속 GET 이 항상 같은 모양.
            gateExcludes = null;
        }
        Boolean captureParams = request == null ? null : request.captureParams();
        Boolean captureResultSet = request == null ? null : request.captureResultSet();
        Boolean requireEntryRoot = request == null ? null : request.requireEntryRoot();

        jdbc.update(
                """
                        INSERT INTO service_instrument_configs
                            (service_name, capture_params, capture_result_set, require_entry_root, gate_excludes, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT(service_name) DO UPDATE SET
                            capture_params     = excluded.capture_params,
                            capture_result_set = excluded.capture_result_set,
                            require_entry_root = excluded.require_entry_root,
                            gate_excludes      = excluded.gate_excludes,
                            updated_at         = excluded.updated_at
                        """,
                serviceName,
                toInteger(captureParams),
                toInteger(captureResultSet),
                toInteger(requireEntryRoot),
                joinGateExcludes(gateExcludes),
                System.currentTimeMillis());

        return new InstrumentConfigPayload(captureParams, captureResultSet, requireEntryRoot, gateExcludes);
    }

    /**
     * 지시 철회(행 삭제) — 멱등(부재여도 조용히 완료 → 204).
     *
     * <p>DELETE 의미론(docs 동반 명문): 지시를 지워도 agent 가 이미 적용한 인메모리 값은 되돌아가지
     * 않는다(202 에 필드가 안 실릴 뿐). 되돌리려면 값을 명시한 PUT(기동값 복귀 지시 — Q-U5 허용
     * 경로)을 넣거나 JVM 을 재시작한다.
     */
    public void delete(String serviceName) {
        validateServiceName(serviceName);
        jdbc.update("DELETE FROM service_instrument_configs WHERE service_name = ?", serviceName);
    }

    // ─── 검증 helpers — 초과 시 IllegalArgumentException → 컨트롤러 400 { "error": ... } ───

    private static void validateServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 은 비어 있을 수 없습니다.");
        }
        if (serviceName.length() > MAX_SERVICE_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "serviceName 은 " + MAX_SERVICE_NAME_LENGTH + "자 이하여야 합니다.");
        }
    }

    /** 공백 항목 제거 → 개수·항목 길이 상한 검사. null 입력은 "목록 지시 없음" 그대로 통과. */
    private static List<String> validateAndNormalizeGateExcludes(List<String> raw) {
        if (raw == null) {
            return null;
        }
        List<String> cleaned = new ArrayList<>(raw.size());
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > MAX_GATE_EXCLUDE_ITEM_LENGTH) {
                throw new IllegalArgumentException(
                        "gateExcludes 항목은 " + MAX_GATE_EXCLUDE_ITEM_LENGTH + "자 이하여야 합니다.");
            }
            // 저장 형식이 콤마 구분 TEXT 라 항목 안의 콤마는 왕복 정합을 깨뜨린다(FQN 에 콤마 불가 — 정상
            // 입력에서는 나올 수 없는 값이므로 명시 거부가 침묵 분해보다 안전).
            if (trimmed.indexOf(',') >= 0) {
                throw new IllegalArgumentException("gateExcludes 항목에는 콤마를 쓸 수 없습니다.");
            }
            cleaned.add(trimmed);
        }
        if (cleaned.size() > MAX_GATE_EXCLUDES_COUNT) {
            throw new IllegalArgumentException(
                    "gateExcludes 는 " + MAX_GATE_EXCLUDES_COUNT + "개 이하여야 합니다.");
        }
        return List.copyOf(cleaned);
    }

    // ─── 저장 형식 변환 — INTEGER(NULL/0/1) ↔ Boolean, 콤마 구분 TEXT ↔ List ───

    private static Integer toInteger(Boolean value) {
        if (value == null) {
            return null;
        }
        return value ? 1 : 0;
    }

    private static Boolean toBoolean(Object stored) {
        if (stored == null) {
            return null;
        }
        return ((Number) stored).intValue() != 0;
    }

    private static String joinGateExcludes(List<String> gateExcludes) {
        if (gateExcludes == null || gateExcludes.isEmpty()) {
            // 빈 목록 지시와 "지시 없음"을 저장에서 구분하지 않는다 — agent 측에서 둘 다
            // "게이트 exclude 목록 없음"과 동일 효과(기동값 = 빈 목록 = 최대 계측)라 정보 손실 0.
            return null;
        }
        return String.join(",", gateExcludes);
    }

    /** {@code AgentConfig.parseCommaList} 전례와 동형 — trim + 빈 항목 제거. NULL/빈 → null(지시 없음). */
    private static List<String> splitGateExcludes(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : stored.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? null : List.copyOf(out);
    }
}
