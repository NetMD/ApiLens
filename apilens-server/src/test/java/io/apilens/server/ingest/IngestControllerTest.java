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

import io.apilens.common.IngestRequest;
import io.apilens.server.instrument.config.InstrumentConfigPayload;
import io.apilens.server.instrument.config.ServiceInstrumentConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IngestController MockMvc 단위 테스트 — 수신 일시정지 503 분기 + 정상 202 계약.
 *
 * <p>[Phase R15] standaloneSetup + Mockito mock IngestService. IngestPauseState 는 실제 인스턴스
 * (시간 소스 주입)로 cap 무관 즉시 상태 제어. NFR-06 — Thread.sleep 0(시각 주입 결정적).
 *
 * <p>[봉인#1 NFR-04] IngestService 시그니처는 mock 으로만 사용 — 생성자/ingest() 시그니처 불변 확인.
 */
class IngestControllerTest {

    private static final String VALID_BODY = "{\"spans\":[]}";

    private IngestService service;
    private IngestPauseState pauseState;
    private ServiceInstrumentConfigService configService;
    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        service = mock(IngestService.class);
        // 시각 소스 주입 — cap 트리거 없이 pause/resume 상태만 제어(결정적).
        pauseState = new IngestPauseState(() -> 0L);
        // [Phase R20] R20/AC-04-1 — 3-인자(202 config piggyback 조립점 협력자). 기본 mock 은 부재(empty).
        configService = mock(ServiceInstrumentConfigService.class);
        when(configService.find(anyString())).thenReturn(Optional.empty());
        IngestController controller = new IngestController(service, pauseState, configService);
        this.mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /**
     * [Phase R15] AC-A2-1 verbatim: "수신 일시정지 중에는 POST /v1/spans 가 503 + Retry-After:60 으로 응답한다".
     * 정방향: returns 503 when paused.
     */
    @Test
    void returns503WhenPaused() throws Exception {
        pauseState.pause();

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())          // 503
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").exists());
    }

    /**
     * [Phase R15] AC-A2-2 verbatim: "수신 중에는 POST /v1/spans 가 202 + { accepted, traces } 로 응답한다".
     * 정방향: accepts 202 when receiving.
     */
    @Test
    void accepts202WhenReceiving() throws Exception {
        when(service.ingest(any(IngestRequest.class))).thenReturn(new IngestResponse(3, 1));

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isAccepted())                    // 202
                .andExpect(jsonPath("$.accepted").value(3))
                .andExpect(jsonPath("$.traces").value(1));
    }

    /**
     * [Phase R15] AC-A2-3 verbatim: "일시정지 중 pause 체크가 mask/validate 전에 실행되어 service.ingest()
     * 가 호출되지 않는다" — mask 미실행을 service 미호출로 증명. 정방향: skips service when paused.
     */
    @Test
    void skipsServiceWhenPaused() throws Exception {
        pauseState.pause();

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable());

        // pause 체크가 service.ingest() 호출 전 → validate/mask/truncate/DB write 전부 skip.
        verify(service, never()).ingest(any());
    }

    /**
     * [Phase R15] AC-A2-4 — 503 응답이 503 의도 형식({ "error": ... })이고 400 오매핑이 아님.
     * 정방향: returns 503 not 400 when paused.
     */
    @Test
    void returns503Not400WhenPaused() throws Exception {
        pauseState.pause();

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())          // 503 — 400 아님
                .andExpect(jsonPath("$.error").value("서버가 유지보수 중이라 잠시 수신을 멈췄습니다."));
    }

    // ── [Phase R20] R20/AC-04-1/AC-04-2 — 202 config piggyback 단일 조립점 (B-14/B-22) ──

    private static final String ONE_SPAN_BODY = """
            {"spans":[{"spanId":"s1","traceId":"t1","parentSpanId":null,"serviceName":"svc-a",
            "operationName":"op","spanKind":"SERVER","startTime":1,"endTime":2,"status":"OK",
            "attributes":null,"payloads":[]}]}
            """;

    /**
     * R20/AC-04-1 verbatim (비협상): "202 body 는 <b>additive only — 기존 두 필드(accepted·traces)
     * 형식 불변, 새 필드 추가만 허용</b>". 정방향: config 있는 서비스 → 세 필드 동반, 기존 두 필드 불변.
     */
    @Test
    void attachesInstrumentConfigWhenServiceHasOne() throws Exception {
        when(service.ingest(any(IngestRequest.class))).thenReturn(new IngestResponse(1, 1));
        when(configService.find("svc-a")).thenReturn(Optional.of(
                new InstrumentConfigPayload(false, null, true, List.of("com.foo.Bar"))));

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONE_SPAN_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))          // 기존 필드 불변
                .andExpect(jsonPath("$.traces").value(1))            // 기존 필드 불변
                .andExpect(jsonPath("$.instrumentConfig.captureParams").value(false))
                .andExpect(jsonPath("$.instrumentConfig.requireEntryRoot").value(true))
                .andExpect(jsonPath("$.instrumentConfig.gateExcludes[0]").value("com.foo.Bar"))
                // 지시 없음(null) 축은 키 자체 생략(@JsonInclude NON_NULL — 부재 허용형 대칭).
                .andExpect(jsonPath("$.instrumentConfig.captureResultSet").doesNotExist());
    }

    /**
     * R20/AC-04-2 verbatim (비협상): "새 202 config 필드는 <b>부재 허용형(옵셔널)</b> — 필드가 없어도
     * 소비 측이 깨지지 않는다". 정방향: config 없는 서비스 → JSON 에 instrumentConfig 키 자체 없음(B-14).
     */
    @Test
    void omitsInstrumentConfigKeyWhenServiceHasNone() throws Exception {
        when(service.ingest(any(IngestRequest.class))).thenReturn(new IngestResponse(1, 1));
        when(configService.find("svc-a")).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONE_SPAN_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.traces").value(1))
                .andExpect(jsonPath("$.instrumentConfig").doesNotExist());
    }

    /** 조회 실패 폴백 — 이미 커밋된 적재의 202 를 500 으로 바꾸지 않는다(config 미탑재 폴백). */
    @Test
    void keepsAccepted202WhenConfigLookupFails() throws Exception {
        when(service.ingest(any(IngestRequest.class))).thenReturn(new IngestResponse(1, 1));
        when(configService.find(anyString())).thenThrow(new RuntimeException("lookup contention"));

        mockMvc.perform(post("/v1/spans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ONE_SPAN_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.instrumentConfig").doesNotExist());
    }
}
