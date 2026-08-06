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

import io.apilens.agent.config.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R20] RemoteConfigGate — reduce-only 3분지 + best-effort 파싱 단위 테스트.
 *
 * <p>R20/AC-05-2 verbatim (비협상): "reduce-only 는 <b>agent 측 강제</b>(불변식 4 — server 불신).
 * 기준점 = <b>JVM 기동 {@code -D} 값</b>(Q-U5 verbatim). server 가 무엇을 보내든: 기동값 이하로
 * 줄이기 = 적용 / <b>기동값까지 되돌리기 = 적용(허용)</b> / 기동값 초과 확대 = <b>폐기</b>.
 * 세 방향 전부 테스트로 확인." — 복귀 적용은 가드 위반이 아니라 정당 허용 경로다(오판 금지 #4).
 *
 * <p>R20/AC-05-1 verbatim (비협상): "파싱 실패(비 JSON·거대 문자열·예상 밖 타입) 시 <b>config 적용만
 * 건너뛰고</b> SUCCESS 판정·전송 흐름·RETRYABLE 재시도 계정 불변" — 본 클래스는 "throw 0 + 무적용"
 * 축을, transport 축(SUCCESS 계정)은 {@code HttpTransportTest} 가 확인한다.
 *
 * <p>security 검증 1순위 축 = captureResultSet(기동 false + 원격 true 지시 → 폐기): 이 축은 매처
 * 미등록 구조 보장이 없어 3분지가 유일 방어선이다.
 */
class RemoteConfigGateTest {

    @BeforeEach
    void resetGates() {
        InstrumentationInstaller.DEBUG = false;
        InstrumentationInstaller.CAPTURE_PARAMS = true;
        InstrumentationInstaller.CAPTURE_RESULT_SET = false;
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of();
    }

    @AfterEach
    void restoreDefaults() {
        InstrumentationInstaller.CAPTURE_PARAMS = true;
        InstrumentationInstaller.CAPTURE_RESULT_SET = false;
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
        InstrumentationInstaller.GATE_EXCLUDED_NAMES = Set.of();
        // 기동 기준점을 표준 기본값으로 복원(다른 테스트 간섭 0).
        RemoteConfigGate.init(launchConfig(true, false, false));
    }

    // ─── B-09: captureParams 축 (기동 true 표준) ─────────────────────────────

    /** ① 이하(줄임) 지시 — false 적용. */
    @Test
    void appliesCaptureParamsOffAsReduce() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort(body("{\"captureParams\": false}"));

        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS, "줄이는 방향(false)은 항상 적용");
    }

    /** ② 복귀 지시 — 원격 false 후 true(=기동값) 되돌리기 적용(정당 허용 경로 — 실수 복구에 재시작 불요). */
    @Test
    void appliesCaptureParamsRevertToLaunchValue() {
        RemoteConfigGate.init(launchConfig(true, false, false));
        RemoteConfigGate.applyBestEffort(body("{\"captureParams\": false}"));
        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS);

        RemoteConfigGate.applyBestEffort(body("{\"captureParams\": true}"));

        assertTrue(InstrumentationInstaller.CAPTURE_PARAMS,
                "기동값(true)까지 되돌리기 = 적용 — 가드 위반이 아니라 Q-U5 허용 경로");
    }

    /** ③ 초과 확대 지시 — 기동 false 인데 true 지시 → 폐기. */
    @Test
    void discardsCaptureParamsExpandBeyondLaunch() {
        RemoteConfigGate.init(launchConfig(false, false, false));
        InstrumentationInstaller.CAPTURE_PARAMS = false;   // 기동 false 상태 재현

        RemoteConfigGate.applyBestEffort(body("{\"captureParams\": true}"));

        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS,
                "기동값 초과 확대는 폐기 — server 가 무엇을 보내든(불변식 4)");
    }

    // ─── B-09: captureResultSet 축 (security 1순위 — 3분지가 유일 방어선) ─────

    /** ③ 기동 false + 원격 true 지시 → 폐기 (이 축은 매처 미등록 구조 보장이 없다 — 유일 방어선). */
    @Test
    void discardsCaptureResultSetExpandBeyondLaunch() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort(body("{\"captureResultSet\": true}"));

        assertFalse(InstrumentationInstaller.CAPTURE_RESULT_SET,
                "captureResultSet 확대 지시 폐기 — 3분지가 유일 방어선(구조 보장 없음)");
    }

    /** ① 기동 true 에서 false(줄임) 적용. */
    @Test
    void appliesCaptureResultSetOffAsReduce() {
        RemoteConfigGate.init(launchConfig(true, true, false));
        InstrumentationInstaller.CAPTURE_RESULT_SET = true;

        RemoteConfigGate.applyBestEffort(body("{\"captureResultSet\": false}"));

        assertFalse(InstrumentationInstaller.CAPTURE_RESULT_SET);
    }

    /** ② 기동 true — false 후 true 복귀 적용. */
    @Test
    void appliesCaptureResultSetRevertToLaunchValue() {
        RemoteConfigGate.init(launchConfig(true, true, false));
        InstrumentationInstaller.CAPTURE_RESULT_SET = false;   // 원격으로 줄인 상태 재현

        RemoteConfigGate.applyBestEffort(body("{\"captureResultSet\": true}"));

        assertTrue(InstrumentationInstaller.CAPTURE_RESULT_SET, "기동값 true 로의 복귀는 적용");
    }

    // ─── B-09: requireEntryRoot 축 (방향 반전 — true 가 줄이는 쪽) ────────────

    /** ① true 지시(억제 켬 = 줄임) — 항상 적용. */
    @Test
    void appliesRequireEntryRootOnAsReduce() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort(body("{\"requireEntryRoot\": true}"));

        assertTrue(InstrumentationInstaller.REQUIRE_ENTRY_ROOT, "억제 켬 = 줄이는 방향 — 항상 적용");
    }

    /** ② 기동 false — true 후 false(=기동값) 복귀 적용. */
    @Test
    void appliesRequireEntryRootRevertToLaunchValue() {
        RemoteConfigGate.init(launchConfig(true, false, false));
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;   // 원격으로 켠 상태 재현

        RemoteConfigGate.applyBestEffort(body("{\"requireEntryRoot\": false}"));

        assertFalse(InstrumentationInstaller.REQUIRE_ENTRY_ROOT, "기동값(false) 복귀는 적용");
    }

    /** ③ 기동 true(-D 로 억제 켬) — false 지시(억제 끔 = trace 확대) → 폐기. */
    @Test
    void discardsRequireEntryRootExpandBeyondLaunch() {
        RemoteConfigGate.init(launchConfig(true, false, true));
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;

        RemoteConfigGate.applyBestEffort(body("{\"requireEntryRoot\": false}"));

        assertTrue(InstrumentationInstaller.REQUIRE_ENTRY_ROOT,
                "기동 -D 로 켠 억제를 원격이 끄는 것은 확대 — 폐기");
    }

    // ─── B-09: gateExcludes — 전체 교체 항상 적용 (기동값 = 빈 목록 = 최대 계측) ─

    /** 설정 → 교체 → 빈 목록: 세 단계 모두 적용(어떤 목록이든 "기동값 이하"). */
    @Test
    void appliesGateExcludesFullReplacementAlways() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort(body("{\"gateExcludes\": [\"com.foo.Bar\", \"com.foo.Baz\"]}"));
        assertEquals(Set.of("com.foo.Bar", "com.foo.Baz"), InstrumentationInstaller.GATE_EXCLUDED_NAMES);

        RemoteConfigGate.applyBestEffort(body("{\"gateExcludes\": [\"com.foo.Qux\"]}"));
        assertEquals(Set.of("com.foo.Qux"), InstrumentationInstaller.GATE_EXCLUDED_NAMES,
                "누적이 아니라 전체 교체 — 목록 축소도 Q-U5 허용 경로");

        RemoteConfigGate.applyBestEffort(body("{\"gateExcludes\": []}"));
        assertEquals(Set.of(), InstrumentationInstaller.GATE_EXCLUDED_NAMES, "빈 목록 = 기동값 복귀");
    }

    // ─── B-10: best-effort — 어떤 입력에도 throw 0 + 무적용 (E-01) ────────────

    /** 비 JSON / 거대 문자열 / 예상 밖 타입 / instrumentConfig 부재 / 빈 body / null — 전 케이스 무해. */
    @Test
    void keepsGatesUntouchedOnMalformedBodies() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort("this is not json at all {{{");
        RemoteConfigGate.applyBestEffort("x".repeat(1_000_000));                     // 거대 문자열
        RemoteConfigGate.applyBestEffort(body("{\"captureParams\": \"yes\"}"));      // 예상 밖 타입 → 그 필드만 skip
        RemoteConfigGate.applyBestEffort(body("{\"gateExcludes\": \"not-an-array\"}"));
        RemoteConfigGate.applyBestEffort("{\"accepted\": 1, \"traces\": 1}");        // instrumentConfig 부재
        RemoteConfigGate.applyBestEffort("{\"instrumentConfig\": 42}");              // 비객체
        RemoteConfigGate.applyBestEffort("");
        RemoteConfigGate.applyBestEffort(null);

        assertTrue(InstrumentationInstaller.CAPTURE_PARAMS, "무적용 — 게이트 불변");
        assertFalse(InstrumentationInstaller.CAPTURE_RESULT_SET);
        assertFalse(InstrumentationInstaller.REQUIRE_ENTRY_ROOT);
        assertEquals(Set.of(), InstrumentationInstaller.GATE_EXCLUDED_NAMES);
    }

    /** 미래 하위 필드 동봉 — 아는 필드만 적용, 미지 필드는 관대 통과(부재 허용형의 agent 측 대칭). */
    @Test
    void appliesKnownFieldsAndIgnoresUnknownSiblings() {
        RemoteConfigGate.init(launchConfig(true, false, false));

        RemoteConfigGate.applyBestEffort(body(
                "{\"captureParams\": false, \"futureKnob\": {\"nested\": true}}"));

        assertFalse(InstrumentationInstaller.CAPTURE_PARAMS, "아는 필드는 적용");
        assertFalse(InstrumentationInstaller.REQUIRE_ENTRY_ROOT, "미지 필드는 무시 — throw 0");
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    /** 202 응답 모양으로 감싼다 — { accepted, traces, instrumentConfig: {...} }. */
    private static String body(String instrumentConfigJson) {
        return "{\"accepted\": 1, \"traces\": 1, \"instrumentConfig\": " + instrumentConfigJson + "}";
    }

    /** 기동 -D 기준점 구성 helper (InstrumentationInstallerCaptureParamsTest.stubConfig 동형). */
    private static AgentConfig launchConfig(boolean captureParams, boolean captureResultSet,
                                            boolean requireEntryRoot) {
        return new AgentConfig(
                true, null, "http://localhost:8765", "test-svc",
                AgentConfig.DEFAULT_SAMPLING_RATE,
                AgentConfig.DEFAULT_BATCH_MAX_SIZE,
                AgentConfig.DEFAULT_BATCH_FLUSH_INTERVAL_MS,
                AgentConfig.DEFAULT_QUEUE_CAPACITY,
                AgentConfig.DEFAULT_PAYLOAD_MAX_BYTES,
                false,
                captureResultSet,
                captureParams,
                List.of(),
                requireEntryRoot);
    }
}
