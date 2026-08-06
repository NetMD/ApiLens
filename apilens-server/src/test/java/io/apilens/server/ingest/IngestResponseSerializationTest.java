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
package io.apilens.server.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.server.instrument.config.InstrumentConfigPayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R20] R20/AC-04-1/AC-04-2 — IngestResponse 직렬화 봉인 (B-22).
 *
 * <p>R20/AC-04-1 verbatim (비협상): "202 body 는 <b>additive only — 기존 두 필드(accepted·traces)
 * 형식 불변, 새 필드 추가만 허용</b>(Q-U3 verbatim). 기존 필드의 이름·타입·의미 변경 0."
 * 2-인자 생성(기존 호출 경로) → JSON 에 instrumentConfig 키 자체가 없어야 GT-3 additive 봉인이 선다.
 */
class IngestResponseSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 2-인자 생성(IngestService 기존 경로) — instrumentConfig 키 부재(@JsonInclude NON_NULL). */
    @Test
    void omitsInstrumentConfigKeyOnLegacyTwoArgConstructor() throws Exception {
        String json = MAPPER.writeValueAsString(new IngestResponse(100, 42));

        assertTrue(json.contains("\"accepted\":100"), "기존 필드 이름·타입 불변");
        assertTrue(json.contains("\"traces\":42"), "기존 필드 이름·타입 불변");
        assertFalse(json.contains("instrumentConfig"),
                "config 없으면 JSON 에 키 자체 부재 — 구 agent(무파싱)·FE factory 영향 0");
    }

    /** 3-인자 생성 — instrumentConfig 동반 + 하위 null 축 키 생략(부재 허용형 대칭). */
    @Test
    void serializesInstrumentConfigWithNullAxesOmitted() throws Exception {
        IngestResponse response = new IngestResponse(1, 1,
                new InstrumentConfigPayload(false, null, true, List.of("com.foo.Bar")));

        String json = MAPPER.writeValueAsString(response);

        assertTrue(json.contains("\"instrumentConfig\""));
        assertTrue(json.contains("\"captureParams\":false"));
        assertTrue(json.contains("\"requireEntryRoot\":true"));
        assertTrue(json.contains("\"gateExcludes\":[\"com.foo.Bar\"]"));
        assertFalse(json.contains("captureResultSet"), "지시 없음(null) 축은 하위 키도 생략");
    }

    /** 2-인자 생성자는 3-인자 canonical 의 null 위임 — 필드 값 동등성 자기증명. */
    @Test
    void twoArgConstructorDelegatesWithNullConfig() {
        IngestResponse legacy = new IngestResponse(3, 2);

        assertEquals(3, legacy.accepted());
        assertEquals(2, legacy.traces());
        assertEquals(new IngestResponse(3, 2, null), legacy);
    }
}
