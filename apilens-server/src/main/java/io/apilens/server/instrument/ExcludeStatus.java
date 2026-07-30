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

/**
 * Whether a class shown in the ranking can actually be excluded by the agent option.
 *
 * <p>// [Phase R19] AC-06-1/AC-06-2 — 3분류 고정. 사용자 명시 비협상 결정(D-12).
 * // "불확실을 가능으로 반올림하지 않는다" 가 이 enum 이 3값인 이유다 — 2값이면
 * // 애매한 것을 어느 한쪽으로 반올림할 수밖에 없고, 그 방향이 {@code EXCLUDABLE} 이면
 * // "옵션에 넣었는데 조용히 아무 일도 안 일어나는" 사고를 도구가 자동화한다.
 */
public enum ExcludeStatus {

    /** 화면에 보이는 이름이 곧 계측이 걸리는 이름이다 — 옵션에 넣으면 실제로 빠진다. */
    EXCLUDABLE,

    /** 이 이름으로는 뺄 수 없다는 것이 확실하다(계측이 걸리는 이름이 다르다 / 클래스 이름 자체가 없다). */
    NOT_EXCLUDABLE,

    /** 뺄 수 있는지 서버가 가진 자료만으로는 확인되지 않는다. 안전 방향. */
    UNKNOWN
}
