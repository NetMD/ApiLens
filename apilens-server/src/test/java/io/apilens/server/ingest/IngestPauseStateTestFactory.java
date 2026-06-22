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

import java.util.function.LongSupplier;

/**
 * Test-only factory exposing the package-private {@link IngestPauseState#IngestPauseState(LongSupplier)}
 * to other test packages (e.g. {@code io.apilens.server.retention}) without widening production visibility.
 *
 * <p>[Phase R15] NFR-06 — cap 경계 결정적 주입을 cross-package 테스트(MaintenanceControllerTest)에서도
 * 쓰게 하되, production 의 패키지 전용 테스트 생성자 가시성(Design §2.1 봉인)은 그대로 유지한다.
 * 사용자 명시 비협상 결정(D08 — cap 자가 재개)을 결정적으로 재현하기 위한 테스트 인프라.
 */
public final class IngestPauseStateTestFactory {

    private IngestPauseStateTestFactory() {
    }

    /** 시간 소스를 주입한 IngestPauseState 를 만든다(production 가시성 불변, 테스트 결정성 확보). */
    public static IngestPauseState withClock(LongSupplier nowMs) {
        return new IngestPauseState(nowMs);
    }
}
