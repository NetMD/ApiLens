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
package io.apilens.server.setup.dto;

/**
 * Service registration item supplied to {@code POST /v1/setup/complete}.
 *
 * <p>[Phase H] AC-06-3 — Q-06 wizard 다중 서비스 forward-compat. 사용자 명시 결정 (UI 는 단건이지만 server 는 List 수용).
 *
 * @param name  service name (영문/숫자/하이픈/언더스코어 only, validation 은 SetupService 에서 수행)
 */
public record ServiceRegistration(
        String name
) {
}
