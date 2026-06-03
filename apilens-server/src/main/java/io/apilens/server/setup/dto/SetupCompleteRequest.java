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

import java.util.List;

/**
 * Request body of {@code POST /v1/setup/complete}.
 *
 * <p>[Phase H] AC-06-2 — Q-01 services nullable optional. 사용자 명시 결정 (skip 분기 허용).
 * services 가 omit/null/[] 모두 200 으로 정규화 (SetupService 내부).
 *
 * @param serverUrl  ApiLens server 접근 가능 URL (http:// 또는 https:// 시작)
 * @param services   wizard 에서 등록할 service 목록 (nullable — skip 분기에서 null)
 */
public record SetupCompleteRequest(
        String serverUrl,
        List<ServiceRegistration> services
) {
}
