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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * {@code /v1/services/{serviceName}/instrument-config} — 서비스별 원격 계측 설정 API.
 *
 * <p>[Phase R20] R20/AC-03-2 — API 우선 출발(Q-U6, 사용자 명시 비협상 결정 — 당시 NAS 실물 검증은 curl).
 * [Phase R21] R21/AC-08-2 (G-07 현행화) — 이제 설정 화면(Services 표의 [계측 설정] 버튼,
 * {@code /services?config=서비스명})과 API 양쪽에서 쓴다. curl 은 화면 없이도 동작하는 동등 경로로 유지.
 *
 * <p>[Phase R20] R20/AC-03-3 — 인증: {@code /v1/**} 하위라 {@code AuthWhitelist} <b>diff 0</b> 상태에서
 * default-deny 자동 보호(불변식 6 — 사용자 명시 비협상 결정. 키 설정 시 토큰 필수, 무키 시 무인증
 * 폴백). 인증 보강 착수 금지 — 이번 라운드에 하면 결정 위반(NFR-04, 별도 인증 라운드).
 * 무키 폴백 환경에서 이 API 는 LAN 신뢰 전제다 — 최악은 계측 꺼짐(가용성)이며, 그 성립 전제는
 * agent 측 reduce-only 강제(불변식 4)다.
 *
 * <p>에러 응답은 기존 flat 표준 {@code { "error": "<message>" }} ({@code IngestController} 전례 동형).
 */
@RestController
public class ServiceInstrumentConfigController {

    private final ServiceInstrumentConfigService service;

    public ServiceInstrumentConfigController(ServiceInstrumentConfigService service) {
        this.service = service;
    }

    /**
     * 전체 교체 저장(멱등 — PK upsert). 응답 200 = 저장된 config echo.
     */
    @Operation(summary = "서비스별 원격 계측 설정 저장 (전체 교체·멱등) — agent 는 줄이는 방향만 적용(기준점 = JVM 기동 -D 값)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 설정 echo",
                    content = @Content(schema = @Schema(implementation = InstrumentConfigPayload.class))),
            @ApiResponse(responseCode = "400", description = "입력 검증 실패 (serviceName 공백/길이, gateExcludes 개수·항목 길이 초과 등)",
                    content = @Content(schema = @Schema(example = "{\"error\":\"...\"}")))
    })
    @PutMapping("/v1/services/{serviceName}/instrument-config")
    public InstrumentConfigPayload put(@PathVariable String serviceName,
                                       @RequestBody InstrumentConfigPayload request) {
        return service.put(serviceName, request);
    }

    /**
     * 조회 — 행 부재 시 404 (config 미설정 서비스).
     */
    @Operation(summary = "서비스별 원격 계측 설정 조회 — 미설정이면 404")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장된 설정",
                    content = @Content(schema = @Schema(implementation = InstrumentConfigPayload.class))),
            @ApiResponse(responseCode = "404", description = "설정 없음",
                    content = @Content(schema = @Schema(example = "{\"error\":\"...\"}")))
    })
    @GetMapping("/v1/services/{serviceName}/instrument-config")
    public ResponseEntity<?> get(@PathVariable String serviceName) {
        Optional<InstrumentConfigPayload> found = service.find(serviceName);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "설정이 없습니다: 해당 서비스에 저장된 원격 계측 설정이 없습니다."));
        }
        return ResponseEntity.ok(found.get());
    }

    /**
     * 지시 철회(행 삭제) — 멱등, 부재여도 204.
     *
     * <p>DELETE 의미론: 지시를 지워도 agent 인메모리 값은 되돌아가지 않는다(202 에 필드가 안 실릴 뿐).
     * 되돌리려면 기동값 복귀를 명시한 PUT(Q-U5 허용 경로) 또는 JVM 재시작.
     */
    @Operation(summary = "서비스별 원격 계측 설정 철회 (멱등) — agent 에 이미 적용된 값은 되돌리지 않음(복귀는 PUT 또는 JVM 재시작)")
    @DeleteMapping("/v1/services/{serviceName}/instrument-config")
    public ResponseEntity<Void> delete(@PathVariable String serviceName) {
        service.delete(serviceName);
        return ResponseEntity.noContent().build();
    }

    /** {@code IngestController} 전례 동형 — IllegalArgumentException → 400 flat {@code { "error": ... }}. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleValidation(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * [Phase R21] R21/AC-08-1 (I-3) — JSON 파싱 단계 400 도 기존 flat 표준 {@code { "error": ... }} 로
     * (검증 400 은 위 {@link #handleValidation} 기존 그대로 — 무접촉). 컨트롤러 로컬 핸들러 —
     * {@code @ControllerAdvice} 신설 금지(cross-cutting 신설 0 기준선).
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleUnreadableBody(HttpMessageNotReadableException e) {
        // 고정 문구 — 파서 예외 메시지는 요청 본문 조각을 되울릴 수 있어 그대로 노출하지 않는다(정보 노출 최소화).
        return Map.of("error", "요청 본문(JSON)을 읽을 수 없습니다.");
    }
}
