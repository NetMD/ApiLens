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
package io.apilens.server.instrument;

import io.apilens.server.instrument.dto.AnalysisRequest;
import io.apilens.server.instrument.dto.AnalysisResponse;
import io.apilens.server.instrument.dto.SimulationRequest;
import io.apilens.server.instrument.dto.SimulationResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * {@code POST /v1/instrument/**} — on-demand instrumentation analysis for one service.
 *
 * <ul>
 *   <li>{@code POST /v1/instrument/analysis} — 서비스 하나의 계측 순위 집계</li>
 *   <li>{@code POST /v1/instrument/simulation} — 고른 대상을 뺐을 때의 예상 결과</li>
 * </ul>
 *
 * <p>⚠️ <b>둘 다 읽기 전용이다.</b> POST 지만 DB 를 한 줄도 쓰지 않는다. POST 인 이유는 (1) 온디맨드
 * 무거운 작업 전례가 전부 POST 이고({@code io.apilens.server.retention.MaintenanceController}),
 * (2) 화면 타임아웃 옵션을 소비하는 헬퍼가 POST 하나뿐이며, (3) 대상 목록(최대 500개 클래스 이름)이
 * URL 로는 길이 한계에 걸리기 때문이다. 의미상의 정확도(GET)를 실측 비용에 양보한 자리라
 * "읽기 전용" 을 여기 javadoc 과 {@code @Operation} 설명에 명시해 오해를 막는다.
 *
 * <p>에러 응답은 {@code SettingsController}·{@code MaintenanceController} 와 동형의 flat 표준
 * {@code { "error": "<message>" }}. 인증 필요(키 설정 시) — R14 default-deny({@code /v1/**} 보호)
 * 자동 계승, {@code AuthWhitelist} 미등재(면제 추가는 결정 위반).
 *
 * <p>// [Phase R19] AC-08-1/AC-08-2/AC-08-7 — 신규 endpoint 2개. 사용자 명시 비협상 결정(S-8).
 * // CLAUDE.md '아키텍처 핵심 원칙' 인용.
 */
@RestController
public class InstrumentAnalysisController {

    /** 서비스 이름 길이 상한 — {@code services.service_name} 성격에 맞춘 값. */
    static final int MAX_SERVICE_NAME_LENGTH = 255;

    /**
     * 시뮬레이션 대상 수 상한. SQLite 의 바인딩 변수 기본 한도는 999 다 —
     * 500개 + 창·서비스 3개 = 503 으로 여유가 있다. 1000개를 허용하면 {@code IN (...)} 이
     * 한도를 넘어 조용히 실패한다.
     */
    static final int SIMULATION_TARGET_CAP = 500;

    /** 클래스 이름 1개 길이 상한. */
    static final int MAX_TARGET_LENGTH = 512;

    /** 시계 편차 여유 — 요청 구간 끝이 이보다 더 미래면 거절한다. */
    static final long FUTURE_SKEW_TOLERANCE_MS = 300_000L;

    private final InstrumentAnalysisService service;

    public InstrumentAnalysisController(InstrumentAnalysisService service) {
        this.service = service;
    }

    /**
     * Rank the classes of one service over a bounded window (read-only).
     */
    @Operation(summary = "서비스 하나의 계측 순위 집계 (온디맨드 · 시간 구간 필수) — 읽기 전용, 동시에 하나만 실행")
    @PostMapping("/v1/instrument/analysis")
    public AnalysisResponse analyze(@RequestBody AnalysisRequest request) {
        String serviceName = requireServiceName(request == null ? null : request.serviceName());
        int windowHours = request == null ? 0 : request.windowHours();
        if (!InstrumentAnalysisService.ALLOWED_WINDOW_HOURS.contains(windowHours)) {
            throw new IllegalArgumentException("windowHours must be one of 1, 6, 24");
        }
        return service.analyze(serviceName, windowHours);
    }

    /**
     * Estimate the effect of excluding the given classes (read-only).
     */
    @Operation(summary = "고른 대상을 뺐을 때의 예상 결과 (용량은 감산 · trace 는 재계산) — 읽기 전용, 동시에 하나만 실행")
    @PostMapping("/v1/instrument/simulation")
    public SimulationResponse simulate(@RequestBody SimulationRequest request) {
        String serviceName = requireServiceName(request == null ? null : request.serviceName());
        long fromMs = request == null ? 0L : request.fromMs();
        long toMs = request == null ? 0L : request.toMs();
        List<String> targets = requireTargets(request == null ? null : request.targets());

        // 구간 길이는 순위 요청과 같은 열거에서만 허용한다 — 창을 요청이 들고 오므로
        // 여기서 막지 않으면 임의 길이 구간이 들어온다.
        long spanMs = toMs - fromMs;
        boolean allowedWindow = InstrumentAnalysisService.ALLOWED_WINDOW_HOURS.stream()
                .anyMatch(hours -> hours * InstrumentAnalysisService.MILLIS_PER_HOUR == spanMs);
        if (!allowedWindow) {
            throw new IllegalArgumentException("window length must be one of 1h, 6h, 24h");
        }
        if (toMs > System.currentTimeMillis() + FUTURE_SKEW_TOLERANCE_MS) {
            throw new IllegalArgumentException("window end is too far in the future");
        }
        return service.simulate(serviceName, fromMs, toMs, targets);
    }

    private static String requireServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName is required");
        }
        if (serviceName.length() > MAX_SERVICE_NAME_LENGTH) {
            throw new IllegalArgumentException("serviceName must be at most " + MAX_SERVICE_NAME_LENGTH + " characters");
        }
        return serviceName;
    }

    /** {@code targets} 는 빈 목록도 정상이다 — 아무것도 빼지 않은 상태를 그대로 재 보는 요청. */
    private static List<String> requireTargets(List<String> targets) {
        if (targets == null) {
            return List.of();
        }
        if (targets.size() > SIMULATION_TARGET_CAP) {
            throw new IllegalArgumentException("targets must be at most " + SIMULATION_TARGET_CAP + " entries");
        }
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("each target must be a non-blank class name");
            }
            if (target.length() > MAX_TARGET_LENGTH) {
                throw new IllegalArgumentException("each target must be at most " + MAX_TARGET_LENGTH + " characters");
            }
        }
        return List.copyOf(targets);
    }

    /** SettingsController 와 동형 — IllegalArgumentException → 400 {@code { "error": ... }}. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * 이미 다른 분석이 도는 중 → 409. 시간 초과(504)와 구분해서 내야 화면이 "잠시 뒤 다시" 와
     * "구간을 좁혀 보세요" 를 구분해 말할 수 있다.
     */
    @ExceptionHandler(InstrumentAnalysisGate.BusyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> handleBusy(InstrumentAnalysisGate.BusyException e) {
        return Map.of("error", e.getMessage());
    }

    /**
     * 실행 시간 상한 초과 → <b>504</b>. 400·500 과 반드시 다른 코드여야 한다.
     *
     * <p>화면은 응답 본문을 노출하지 않고 <b>상태 코드로만</b> 문구를 고른다. 시간 초과를 500 으로
     * 묶으면 "분석에 실패했어요" 만 뜨고, 운영자가 실제로 할 수 있는 유일한 행동인
     * "구간을 좁혀 다시 시도해 주세요" 를 영영 보지 못한다. 인덱스를 더 만들지 않기로 한 결정 아래에서
     * 이 상한은 사실상 유일한 방어선이므로, 그 방어선이 작동했다는 사실이 화면까지 전달되어야 한다.
     */
    @ExceptionHandler(InstrumentAnalysisGate.DeadlineExceededException.class)
    @ResponseStatus(HttpStatus.GATEWAY_TIMEOUT)
    public Map<String, String> handleDeadlineExceeded(InstrumentAnalysisGate.DeadlineExceededException e) {
        return Map.of("error", e.getMessage());
    }
}
