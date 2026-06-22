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

/**
 * A {@link CharSequence} wrapper that checks a deadline on each {@code charAt} call, so that a
 * regex matcher caught in catastrophic backtracking bails out from within its own loop (Design §3.2).
 *
 * <p>// [Phase K] AC-06-1 — R14-D05 비협상: 별도 스레드 없이 deadline 초과 시 자기 예외 탈출
 * // (PM 위임 #1 = CharSequence 시간검사 wrapper 채택 — 데몬 스레드 누수 0). 사용자 명시 비협상 결정.
 * // CLAUDE.md '아키텍처 핵심 원칙' 인용.
 *
 * <p>java.util.regex 매칭 엔진은 backtracking 폭주 시 {@code charAt} 을 반복 호출하므로, 여기서
 * deadline 초과를 감지해 {@link RegexTimeoutException} 으로 빠져나온다. 매 호출마다 nanoTime() 은
 * 비싸므로 {@link #DEADLINE_CHECK_MASK}+1(=1024) 회마다 1번만 체크한다(Design §5 상수).
 */
final class DeadlineCharSequence implements CharSequence {

    /** [Phase K] nanoTime() 호출 빈도 제어 — (counter & MASK)==0 일 때만 체크(1024회마다, Design §5). */
    static final int DEADLINE_CHECK_MASK = 0x3FF;

    private final CharSequence delegate;
    private final long deadlineNanos;
    private int checkCounter;

    DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
    }

    @Override
    public char charAt(int index) {
        // 1024회마다 deadline 검사 — 엄격 초과(>)만 reject (정확 deadline 은 통과, Design §8.1 경계).
        if ((++checkCounter & DEADLINE_CHECK_MASK) == 0 && System.nanoTime() > deadlineNanos) {
            throw RegexTimeoutException.INSTANCE;
        }
        return delegate.charAt(index);
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        // 부분열도 같은 deadline 을 공유해 backtracking 어디서든 탈출 가능.
        return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
