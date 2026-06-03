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
package io.apilens.agent.instrument;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * [R11] AC-F-R11-03 (D-P0-01 비협상 — verbatim 인용)
 *   본질: agent AdviceSupport.serializeReturn → Jackson → FileSystemResource.getOutputStream()
 *         → mp4 0바이트 truncate 차단 (Layer 3 — Jackson MAPPER MixIn 최종 방어)
 *   회귀 가드 grep: 정방향 = `@JsonIgnore` (5 hit) + getter 5종 시그니처 (5 hit) /
 *                    반대방향 = `MAPPER.writeValueAsString(.*Resource` 0 hit (Layer 1·2 차단 후
 *                    여전히 Resource 가 Jackson 에 도달해도 5 getter traverse 0 hit)
 *   CLAUDE.md 인용: "Build 설정 lessons §1 Shadow jar relocate 함정 — @JsonIgnore import 는 raw
 *                    com.fasterxml.jackson.annotation.JsonIgnore 로 작성, shadowJar 가 bytecode 의
 *                    annotation reference 를 자동으로 relocated 패키지로 변환"
 *
 * <p>Jackson MixIn for Spring's {@code org.springframework.core.io.Resource} interface.
 *
 * <p>Layer 3 final defense — even if Layer 1 ({@code AdviceSupport.isUnsafeToSerialize})
 * and Layer 2 ({@code AdviceSupport.unwrapResponseEntity}) both fail to catch a
 * dangerous type, Jackson's traversal of the {@code Resource} hierarchy must NEVER
 * call any of these 5 getters (D-P0-01 비협상):
 *
 * <ul>
 *   <li>{@code getOutputStream()} — {@code Files.newOutputStream(path)} → file truncate (P0 BUG 의 진원지)</li>
 *   <li>{@code getInputStream()} — 잠재적 stream consume side effect</li>
 *   <li>{@code getFile()} — {@code java.io.File} 객체 노출 (Jackson 이 재귀 traverse)</li>
 *   <li>{@code getURI()} / {@code getURL()} — host info 노출 (운영 보안)</li>
 * </ul>
 *
 * <p>{@code @JsonIgnore} import 는 raw {@code com.fasterxml.jackson.annotation.JsonIgnore}
 * 로 작성하고, shadowJar 가 컴파일 bytecode 의 annotation reference 를 자동으로
 * relocate 된 {@code io.apilens.agent.shaded.jackson.annotation.JsonIgnore} 로 변환.
 * MAPPER 도 같은 relocate 패키지 → annotation matching 정합.
 *
 * <p>package-private — public API 표면 0 보장 (instrument 패키지 내부 helper 한정).
 */
abstract class ResourceMixIn {

    @JsonIgnore
    abstract Object getOutputStream();

    @JsonIgnore
    abstract Object getInputStream();

    @JsonIgnore
    abstract Object getFile();

    @JsonIgnore
    abstract Object getURI();

    @JsonIgnore
    abstract Object getURL();
}
