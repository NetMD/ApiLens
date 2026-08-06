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
import io.apilens.server.instrument.config.ServiceInstrumentConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Span ingest endpoint. Agent posts batches here.
 */
@RestController
public class IngestController {

    private final IngestService service;
    // [Phase R15] AC-A2-1 — 수신 일시정지 상태 주입(controller 레이어 분기). 사용자 명시 비협상 결정(D02).
    // CLAUDE.md '아키텍처 핵심 원칙' (Agent 무변경 — server 가 503 으로 수신 차단) 인용.
    private final IngestPauseState pauseState;
    // [Phase R20] R20/AC-04-1 — 202 config piggyback 단일 조립점의 협력자(S-116). 사용자 명시 비협상
    // 결정(Q-U3 additive only). ⚠️ IngestService 생성자 4-인자 봉인 무접촉 — 신규 의존은 controller
    // 레이어 주입(R15 pauseState 전례 동형). CLAUDE.md 'Build 설정 lessons §1' 인용.
    private final ServiceInstrumentConfigService instrumentConfigService;

    // [봉인#1 NFR-04] IngestService 시그니처 불변 — pause 체크는 controller 레이어에서만.
    // R13 287a7e7 회귀 진원지(IngestService 생성자 변경이 통합테스트 컴파일 깨짐).
    // [Phase R20] 2→3-인자(instrumentConfigService 추가만 — R15 의 1→2 전례 동형).
    public IngestController(IngestService service, IngestPauseState pauseState,
                            ServiceInstrumentConfigService instrumentConfigService) {
        this.service = service;
        this.pauseState = pauseState;
        this.instrumentConfigService = instrumentConfigService;
    }

    // [Phase R15] AC-A2-1/AC-A2-3 — 일시정지면 503+Retry-After 로 즉시 응답, service.ingest() 미호출
    //   (validate/mask/truncate/DB write 전부 skip). 사용자 명시 비협상 결정(D02).
    //   CLAUDE.md '아키텍처 핵심 원칙' (Agent 무변경 — server 만 503 으로 수신 멈춤) 인용.
    // [봉인#3] 503 = ResponseEntity 직접 반환(throw 아님 — @ExceptionHandler 400 매핑 회피).
    // [Phase R16] FR-04(최우선) — ResponseEntity<?> 와일드카드라 자동 스키마가 부실 → 손 @ApiResponse 로
    //   202/503/400 이종 응답을 명시(§4.2). 시그니처·[봉인#1]·[봉인#3] 불변, 애노테이션만 추가.
    @Operation(summary = "Span 배치 수신 (agent → server ingest)")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "정상 수신 — 저장된 span 수와 trace 수를 반환",
                    content = @Content(schema = @Schema(implementation = IngestResponse.class))),
            @ApiResponse(responseCode = "503", description = "유지보수 모드(수신 일시정지) 중 — 저장하지 않고 잠시 거절",
                    headers = @Header(name = "Retry-After", description = "재시도 권장 대기(초)",
                            schema = @Schema(type = "integer", example = "60")),
                    content = @Content(schema = @Schema(example = "{\"error\":\"...\"}"))),
            @ApiResponse(responseCode = "400", description = "요청 검증 실패 (필수 필드 누락 등)",
                    content = @Content(schema = @Schema(example = "{\"error\":\"...\"}")))
    })
    @PostMapping("/v1/spans")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest request) {
        if (pauseState.isPaused()) {
            return ResponseEntity.status(503)
                    .header("Retry-After", "60")
                    .body(Map.of("error", "서버가 유지보수 중이라 잠시 수신을 멈췄습니다."));
        }
        IngestResponse response = service.ingest(request);
        // 202 — additive only(GT-3 재정의, Q-U3): 기존 두 필드 { accepted, traces } 형식 불변,
        // 새 필드 추가만 허용. instrumentConfig 는 부재 허용형. @ResponseStatus(ACCEPTED) 제거 후 ResponseEntity 통일.
        return ResponseEntity.accepted().body(attachInstrumentConfig(request, response));
    }

    /**
     * [Phase R20] R20/AC-04-1 — 202 config piggyback <b>단일 조립점</b>(S-116). batch 의 서비스명 =
     * 첫 span 기준(agent 는 단일 서비스 — 모든 span 의 serviceName = config.serviceName(), hello 포함
     * — batch 내 단일이 구조 보장). config 행이 있으면 <b>매 202 마다 무조건</b> 실어 보낸다
     * (self-healing 재적용, W-1 — 변경 감지 없음: agent 재시작으로 기동 -D 값이 복원돼도 다음 202 에서
     * 재적용). 행 부재면 그대로 반환(부재 허용형 — 키 생략).
     *
     * <p>조회 실패(경합 등)는 config 미탑재로 폴백 — 이미 커밋된 적재의 202 를 500 으로 바꾸지 않는다
     * (host-throw-0 계열: agent 재시도로 인한 중복 적재 유발 방지. 다음 202 가 self-healing).
     */
    private IngestResponse attachInstrumentConfig(IngestRequest request, IngestResponse response) {
        try {
            String serviceName = request.spans().get(0).serviceName();
            return instrumentConfigService.find(serviceName)
                    .map(config -> new IngestResponse(response.accepted(), response.traces(), config))
                    .orElse(response);
        } catch (Exception e) {
            return response;
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
