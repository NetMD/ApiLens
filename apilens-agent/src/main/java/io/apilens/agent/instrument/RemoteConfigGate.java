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
package io.apilens.agent.instrument;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.agent.config.AgentConfig;

import java.util.HashSet;
import java.util.Set;

/**
 * Applies the server-piggybacked instrument config (202 body {@code instrumentConfig})
 * to the runtime volatile gates — reduce-only, enforced on the agent side.
 *
 * <p>[Phase R20] R20/AC-05-2 — reduce-only 는 <b>agent 측 강제</b>(불변식 4 — server 불신).
 * 사용자 명시 비협상 결정(Q-U5 verbatim: 기준점 = <b>"기동 {@code -D} 값"</b> — 기동값 이하로
 * 줄이기 자유 + 기동값까지 되돌리기 허용 + 기동값 초과 확대는 폐기).
 * <b>특히 captureResultSet·requireEntryRoot 축은 이 3분지가 유일 방어선</b>이다 — 매처 미등록
 * 구조 보장은 capture-params 축 전용(그 축의 소비 advice 는 항상 weaving 되고 런타임 volatile
 * 분기만 남기 때문). CLAUDE.md '아키텍처 핵심 원칙'(Agent 자체 장애가 호스트 앱에 영향 0) 인용.
 *
 * <p>[Phase R20] R20/AC-05-4 — 게이트 값은 인메모리뿐이다. JVM 재시작 시 기동 {@code -D} 값으로
 * 복원된다(결함 아님 — "원격 OFF 는 영구 설정이 아니다", docs/agent-options.md 동반 명문).
 *
 * <p>[Phase R20] R20/AC-05-5 (BL-R20-08) — 이 게이트는 {@link InstrumentationInstaller} 의 volatile
 * <b>런타임 인메모리 값</b>만 바꾼다. {@code AgentConfig.PROP_CAPTURE_PARAMS} 의 default=true 봉인
 * (D-03 "default change forbidden")과 {@code AgentConfig} 파싱 default 는 한 글자도 바뀌지 않는다 —
 * 두 층을 섞어 읽으면 "봉인 위반" 오독이 된다.
 */
public final class RemoteConfigGate {

    /**
     * 202 body 파싱 전용 mapper — agent 내장(shaded relocate) Jackson 재사용, 신규 의존 0.
     * {@code readTree} 만 쓰므로 모듈 등록 불요.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // [Phase R20] R20/AC-05-2 — 기준점 = JVM 기동 -D 값 (Q-U5 verbatim, 사용자 명시 비협상 결정).
    //   AgentMain.init 에서 1회 설정, 이후 불변. 원격 지시가 아무리 와도 이 기준점은 바뀌지 않는다.
    private static volatile boolean launchCaptureParams;
    private static volatile boolean launchCaptureResultSet;
    private static volatile boolean launchRequireEntryRoot;

    private RemoteConfigGate() {
    }

    /**
     * Capture the JVM-launch {@code -D} baseline. Called once from {@code AgentMain.init}.
     */
    public static void init(AgentConfig config) {
        launchCaptureParams = config.captureParams();
        launchCaptureResultSet = config.captureResultSet();
        launchRequireEntryRoot = config.requireEntryRoot();
    }

    /**
     * Best-effort parse + apply of a 2xx ingest response body. Never throws.
     *
     * <p>[Phase R20] R20/AC-05-1 — <b>"202 파싱 실패 ≠ 전송 실패"</b>(W-13, NFR-01 확장 — 사용자
     * 명시 비협상 결정). 어떤 입력(비 JSON·거대 문자열·예상 밖 타입·미래 필드·null·빈 body)에도
     * throw 하지 않는다 — config 적용만 건너뛰고 {@code HttpTransport} 의 SUCCESS 판정·전송 흐름·
     * RETRYABLE 재시도 계정에 관여하지 않는다.
     */
    public static void applyBestEffort(String body) {
        try {
            if (body == null || body.isEmpty()) {
                return;
            }
            JsonNode root = MAPPER.readTree(body);
            JsonNode cfg = root.get("instrumentConfig");
            // 부재 허용형 — 노드 부재/비객체면 조용히 return (config 없는 서비스가 정상 상태).
            if (cfg == null || !cfg.isObject()) {
                return;
            }
            // 각 하위 필드는 타입 검사 후 개별 적용 — 예상 밖 타입은 그 필드만 skip (관대 파싱,
            // server 가 미래에 하위 필드를 추가해도 통과 — 부재 허용형의 agent 측 대칭).
            applyCaptureParams(cfg.get("captureParams"));
            applyCaptureResultSet(cfg.get("captureResultSet"));
            applyRequireEntryRoot(cfg.get("requireEntryRoot"));
            applyGateExcludes(cfg.get("gateExcludes"));
        } catch (Throwable t) {
            // config 적용만 건너뛴다. SUCCESS 판정·전송 흐름·RETRYABLE 재시도 계정 불변(W-13).
            if (InstrumentationInstaller.DEBUG) {
                System.err.println("[ApiLens][REMOTE-CONFIG] apply skipped: "
                        + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    /**
     * reduce-only 3분지 (BL-R20-02) — captureParams 축:
     * desired==false(줄임) ‖ desired==기동값(복귀) → 적용, 그 외(기동값 초과 확대) 폐기.
     * ②복귀는 가드 위반이 아니라 정당 허용 경로다(Q-U5 — 실수 복구에 재시작 불요).
     */
    private static void applyCaptureParams(JsonNode node) {
        if (node == null || !node.isBoolean()) {
            return;
        }
        boolean desired = node.booleanValue();
        if (!desired || desired == launchCaptureParams) {
            InstrumentationInstaller.CAPTURE_PARAMS = desired;
        } else {
            logDiscard("captureParams", desired);
        }
    }

    /**
     * reduce-only 3분지 — captureResultSet 축. <b>이 축은 3분지가 유일 방어선</b>(소비처가 전부
     * 무조건 weaving + 런타임 volatile 분기 — 매처 미등록 구조 보장 없음, security 검증 1순위 축).
     */
    private static void applyCaptureResultSet(JsonNode node) {
        if (node == null || !node.isBoolean()) {
            return;
        }
        boolean desired = node.booleanValue();
        if (!desired || desired == launchCaptureResultSet) {
            InstrumentationInstaller.CAPTURE_RESULT_SET = desired;
        } else {
            logDiscard("captureResultSet", desired);
        }
    }

    /**
     * reduce-only 3분지 — requireEntryRoot 축(방향 반전 — true 가 줄이는 쪽):
     * desired==true(억제 켬 = 줄임) ‖ desired==기동값(복귀) → 적용, 그 외 폐기.
     */
    private static void applyRequireEntryRoot(JsonNode node) {
        if (node == null || !node.isBoolean()) {
            return;
        }
        boolean desired = node.booleanValue();
        if (desired || desired == launchRequireEntryRoot) {
            InstrumentationInstaller.REQUIRE_ENTRY_ROOT = desired;
        } else {
            logDiscard("requireEntryRoot", false);
        }
    }

    /**
     * gateExcludes — 기동값 = 빈 목록 = 최대 계측이므로 어떤 목록이든 "기동값 이하" → 전체 교체
     * 항상 적용(목록 축소·빈 목록 복귀도 Q-U5 허용 경로). 불변 Set 통째 교체(읽기 스레드 안전).
     */
    private static void applyGateExcludes(JsonNode node) {
        if (node == null || !node.isArray()) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (JsonNode item : node) {
            if (item != null && item.isTextual()) {
                String name = item.asText().trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.copyOf(names);
    }

    /** 폐기는 debug 로그만 — 운영 stderr 오염 방지(silent drop 계열). */
    private static void logDiscard(String field, boolean desired) {
        if (InstrumentationInstaller.DEBUG) {
            System.err.println("[ApiLens][REMOTE-CONFIG] discard expand-beyond-launch: "
                    + field + "=" + desired + " (reduce-only — 기준점 = 기동 -D 값)");
        }
    }
}
