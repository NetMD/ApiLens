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
package io.apilens.agent;

import io.apilens.agent.config.AgentConfig;
import io.apilens.agent.instrument.InstrumentationInstaller;
import io.apilens.common.Span;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * [Phase R20] R20/AC-01-3 — hello span 은 깔때기(AdviceSupport/TraceContext) 밖 경로
 * (queue 직접 offer)라 (Q-1) 게이트와 무관하다(불변식 2, 사용자 명시 비협상 결정 — 깨지면
 * R19 P-1 agent 버전 컬럼 회귀). ON 상태에서도 hello span 생성·버전 attribute 가 유지됨을 잠근다.
 * queue/transport 층 kind 필터가 생기면 이 단위 축과 agent↔server 통합 축이 함께 깨진다.
 */
class AgentMainHelloSpanGateTest {

    @AfterEach
    void resetGate() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = false;
    }

    /** ON 상태 — buildHelloSpan 은 게이트를 읽지 않는 경로(깔때기 밖): span·버전 attribute 정상. */
    @Test
    void buildsHelloSpanWithVersionWhileGateOn() {
        InstrumentationInstaller.REQUIRE_ENTRY_ROOT = true;   // (Q-1) ON 재현

        Span hello = AgentMain.buildHelloSpan(new AgentConfig(
                true, null, "http://localhost:8765", "svc",
                AgentConfig.DEFAULT_SAMPLING_RATE, AgentConfig.DEFAULT_BATCH_MAX_SIZE,
                AgentConfig.DEFAULT_BATCH_FLUSH_INTERVAL_MS, AgentConfig.DEFAULT_QUEUE_CAPACITY,
                AgentConfig.DEFAULT_PAYLOAD_MAX_BYTES, false, false, true, List.of(),
                true));   // 기동 -D 로도 ON

        assertNotNull(hello, "hello span 은 깔때기 밖 — 게이트 ON 이어도 생성");
        assertEquals("agent.startup", hello.operationName());
        assertEquals(AgentMain.AGENT_VERSION,
                hello.attributes().get("apilens.agent.version"),
                "서비스별 agent 버전 표시의 원천 attribute 유지(R19 P-1 회귀 가드)");
        assertEquals("0.6.0", AgentMain.AGENT_VERSION, "AGENT_VERSION = 제품 버전 정렬(두 번째 agent 변경 라운드)");
    }
}
