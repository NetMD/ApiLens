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
package io.apilens.common;

/**
 * Signal raised by {@link DeadlineCharSequence} when the regex matcher exceeds the deadline,
 * used to bail out of catastrophic backtracking from within the matching loop.
 *
 * <p>// [Phase R18] AC-06-1 — R14-D05 비협상 승계: 매칭 루프 자체가 deadline 초과 시 자기 예외로 탈출
 * // (Thread.stop 금지 회피, 별도 스레드 0). 사용자 명시 비협상 결정.
 * // [Phase R18] apilens-server → apilens-common 승격 + public 확대(생성자 private 유지 — 싱글턴).
 * // 비협상 봉인: unchecked(RuntimeException) 라 {@code mask(String,String)} 2-arg 시그니처 불변 —
 * // 호출부(ingest degrade / 프리뷰 400)가 이 public 예외를 catch 해 경로별로 흡수한다.
 * // CLAUDE.md '아키텍처 핵심 원칙'(공유 엔진 일관성) 인용.
 *
 * <p>stack trace 불필요(성능) — fillInStackTrace 생략.
 */
public final class RegexTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public static final RegexTimeoutException INSTANCE = new RegexTimeoutException();

    private RegexTimeoutException() {
        super(null, null, false, false);
    }
}
