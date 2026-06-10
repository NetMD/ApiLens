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
package io.apilens.server.masking;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [Phase R12] SEC-R12-02 — startup 마스킹 로드 실패 fail-closed 동작 단위 테스트.
 *
 * <p>사용자 결정(2026-06-11) verbatim 인용: "MaskingEngineHolder.java:72-75 의 startup 로드 실패 →
 * 빈 룰 엔진 폴백(fail-open)을 <b>startup 한정 기동 차단(예외 전파)</b> 으로 변경.
 * runtime reload 실패는 현행 fail-safe(기존 엔진 유지) 그대로."
 *
 * <p>테스트명은 정방향 동사 ({@code propagates*} / {@code keeps*}) — 원하는 동작 자체를 명시
 * (EXT-005 반대 방향 lock-in 방지).
 */
class MaskingEngineHolderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * startup 시점(기존 엔진 없음) 로드 실패 → 예외 전파로 기동 차단 (fail-closed).
     * 빈 룰 엔진 폴백(fail-open) 미수행 — current() 는 null 유지 (마스킹 전무 상태 기동 0).
     */
    @Test
    void propagatesExceptionOnStartupLoadFailure() {
        MaskingRuleRepository repository = mock(MaskingRuleRepository.class);
        RuntimeException dbFailure = new RuntimeException("simulated masking_rules read failure");
        when(repository.findEnabled()).thenThrow(dbFailure);

        MaskingEngineHolder holder = new MaskingEngineHolder(repository, mapper);

        IllegalStateException propagated = assertThrows(IllegalStateException.class, holder::reload,
                "startup 로드 실패는 예외 전파로 기동을 차단해야 한다 (SEC-R12-02 fail-closed)");
        assertSame(dbFailure, propagated.getCause(), "원인 예외를 보존해 기동 실패 로그에서 추적 가능해야 한다");
        assertNull(holder.current(), "빈 룰 엔진 폴백(fail-open) 없이 엔진 미설정 상태가 유지되어야 한다");
    }

    /**
     * runtime reload(기존 엔진 존재) 실패 → 현행 fail-safe 유지: 예외 없이 기존 엔진 그대로.
     * (사용자 결정 verbatim: "runtime reload 실패는 현행 fail-safe(기존 엔진 유지) 그대로")
     */
    @Test
    void keepsPreviousEngineOnRuntimeReloadFailure() {
        MaskingRuleRepository repository = mock(MaskingRuleRepository.class);
        when(repository.findEnabled())
                .thenReturn(List.of())                                          // startup 1회 — 성공
                .thenThrow(new RuntimeException("simulated runtime reload failure")); // runtime — 실패

        MaskingEngineHolder holder = new MaskingEngineHolder(repository, mapper);
        holder.reload(); // startup 성공 (MaskingConfig 동형)
        MaskingEngine startupEngine = holder.current();
        assertNotNull(startupEngine, "startup 성공 시 엔진이 설정되어야 한다");

        holder.reload(); // runtime 실패 — 예외 미전파 (fail-safe)

        assertSame(startupEngine, holder.current(), "runtime reload 실패 시 기존 엔진을 그대로 유지해야 한다");
    }

    /** 정상 경로 회귀 가드 — reload 성공 시 활성 룰이 새 엔진 인스턴스로 교체된다. */
    @Test
    void replacesEngineInstanceOnSuccessfulReload() {
        MaskingRuleRepository repository = mock(MaskingRuleRepository.class);
        when(repository.findEnabled()).thenReturn(List.of());

        MaskingEngineHolder holder = new MaskingEngineHolder(repository, mapper);
        holder.reload();
        MaskingEngine first = holder.current();
        holder.reload();
        MaskingEngine second = holder.current();

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "reload 성공마다 불변 엔진의 새 인스턴스로 교체되어야 한다");
    }
}
