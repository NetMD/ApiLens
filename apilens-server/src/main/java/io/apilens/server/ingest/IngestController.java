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
import io.apilens.common.Span;
import io.apilens.server.instrument.config.ServiceInstrumentConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    // [Phase R21] R21/AC-08-1 (R-02) — 무로그 catch 관측성용 로거(현행 slf4j 로거 부재라 신설).
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    /**
     * [Phase R26] R26/AC-R26-28 — {@code spanId} 길이 상한(글자). 사용자 명시 결정(UA-8 「16진수 32자 이하」).
     *
     * <p>W3C Trace Context 의 span-id 는 16진수 <b>16자</b>이고 계측기도 그 값을 보낸다. 32 는 그 두 배로 잡은
     * 여유다 — 계측기가 보내는 값을 거르지 않으면서, 고아 후보 목록의 64자 잘림(그 쪽 {@code MAX_ID_LENGTH})에
     * <b>닿기 전에</b> 입구에서 끊는다.
     */
    static final int SPAN_ID_MAX_LENGTH = 32;

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
        requireWellFormedSpanIds(request);
        IngestResponse response = service.ingest(request);
        // 202 — additive only(GT-3 재정의, Q-U3): 기존 두 필드 { accepted, traces } 형식 불변,
        // 새 필드 추가만 허용. instrumentConfig 는 부재 허용형. @ResponseStatus(ACCEPTED) 제거 후 ResponseEntity 통일.
        return ResponseEntity.accepted().body(attachInstrumentConfig(request, response));
    }

    /**
     * [Phase R26] R26/AC-R26-27/R26/AC-R26-28 — {@code spanId} 형식 검사. <b>16진수 32자 이하</b>만 통과한다
     * (사용자 명시 결정 UA-8). 밖에서 온 값이 그대로 고아 후보 목록·로그에 실리는 것을 입구에서 끊는다.
     *
     * <p>★<b>왜 {@code IngestService.validate()} 가 아니라 여기인가</b>: 적재 진입점 service 를
     * <b>POJO 로 직접 부르는 agent 모듈 통합 시험</b>이 있고 그 시험의 spanId 는 16진수가 아니다
     * ({@code AgentToServerIntegrationTest} 의 {@code "s-root"} 류). service 안에 넣으면 그 시험이 깨지는데,
     * <b>agent 모듈 파일 무접촉</b>이 이 라운드의 비협상이다. 신규 의존·신규 판정은 진입점 service 가 아니라
     * controller 레이어에 둔다 — R15 {@code pauseState} · R20 {@code instrumentConfigService} 와 같은 전례다.
     * CLAUDE.md 'Build 설정 lessons §1'(shadow jar relocate 함정) 인용.
     *
     * <p>★★<b>{@code traceId} 로 넓히지 말 것</b>: 계측기의 기동 알림 span 은 traceId 가
     * {@code "agent-startup-…"} 이라 <b>16진수가 아니다</b>({@code AgentMain} 의 hello span 조립부).
     * 넓히면 운영 트래픽이 400 이 된다. "대칭을 맞추자" 는 이유로 넓히는 것이 정확히 그 함정이다.
     *
     * <p>★<b>한계 그 자리에</b>: 이 가드는 <b>HTTP 입구만</b> 덮는다. 앞으로 {@code IngestService.ingest()}
     * 를 부르는 새 생산 경로가 생기면 그 경로는 안 덮이므로 <b>그 자리에도 같은 검사를 붙여야 한다</b>.
     * 안 덮인 경로의 동작은 오늘과 같다(과잉 거부 0) — <b>틀리는 방향은 안전한 쪽</b>이다.
     *
     * <p>빈 값·없는 값·null 묶음은 <b>그냥 넘긴다</b> — 그 문면은 {@code IngestService.validate()} 가 이미
     * 갖고 있고, 여기서 먼저 던지면 기존 400 메시지가 바뀐다. <b>거부 방향만 넓히고</b> 통과 갈래는 안 좁힌다.
     */
    private static void requireWellFormedSpanIds(IngestRequest request) {
        if (request == null || request.spans() == null) {
            return;
        }
        for (Span s : request.spans()) {
            if (s == null) {
                continue;
            }
            String id = s.spanId();
            if (id == null || id.isBlank()) {
                continue;
            }
            if (id.length() > SPAN_ID_MAX_LENGTH || !isHexadecimal(id)) {
                throw new IllegalArgumentException(
                        "spanId must be hexadecimal and at most " + SPAN_ID_MAX_LENGTH + " characters");
            }
        }
    }

    /**
     * 손으로 쓴 16진수 판정 — <b>정규식을 쓰지 않는다</b>. 요청마다 패턴을 만들 필요가 없고,
     * 이 파일에 정규식 폭주 표면을 새로 늘리지 않는다.
     */
    private static boolean isHexadecimal(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
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
            // [Phase R21] R21/AC-08-1 (R-02) — config 미탑재 폴백은 유지하되 debug 1줄로 관측 가능하게.
            //   202 반환 동작 diff 0 (폴백 의미론 불변 — 다음 202 가 self-healing).
            log.debug("instrument config attach skipped: {}", e.toString());
            return response;
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }
}
