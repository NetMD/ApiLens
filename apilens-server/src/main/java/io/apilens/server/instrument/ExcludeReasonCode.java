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
 * Why a class is not (or may not be) excludable — a code, never a sentence.
 *
 * <p>// [Phase R19] — 서버는 화면 문구를 보내지 않는다. 문구의 단일 거주지는 UI 쪽 문구표이고
 * // 서버는 코드만 준다. 서버가 한국어 문구를 보내면 문구 한 줄 고치는 데 서버 릴리스가 필요해진다.
 *
 * <p>{@link ExcludeStatus#EXCLUDABLE} 인 항목은 사유 코드가 {@code null} 이다.
 */
public enum ExcludeReasonCode {

    /** span 이름에 클래스 부분이 없다({@code #} 없음 — 예: {@code jdbc.execute}). */
    NO_CLASS_NAME,

    /** 계측이 라이브러리 프록시 한 점에 걸려 있어 화면 이름으로는 못 뺀다(MyBatis mapper 계열). */
    PROXY_INSTRUMENTED,

    /** 뺄 수 있는지 확인되지 않은 경로다(프레임워크 패키지 / 약한 신호). 안전 방향. */
    UNVERIFIED_PATH
}
