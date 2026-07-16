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

import java.util.function.LongSupplier;

/**
 * A {@link CharSequence} wrapper that checks a deadline on each {@code charAt} call, so that a
 * regex matcher caught in catastrophic backtracking bails out from within its own loop.
 *
 * <p>// [Phase R18] AC-06-1 — R14-D05 비협상 승계: 별도 스레드 없이 deadline 초과 시 자기 예외 탈출
 * // (CharSequence 시간검사 wrapper — 데몬 스레드 누수 0). 사용자 명시 비협상 결정.
 * // [Phase R18] apilens-server → apilens-common 승격 + public 확대(공유 엔진 실행 시점 ReDoS
 * // 방어를 ingest·프리뷰 3경로가 공유). 비협상 봉인: 외부 의존 0(CharSequence=JDK) → relocate 무영향.
 * // CLAUDE.md '아키텍처 핵심 원칙'(마스킹은 apilens-common 의 공유 엔진 — 결과 일관성 필수) 인용.
 *
 * <p>java.util.regex 매칭 엔진은 backtracking 폭주 시 {@code charAt} 을 반복 호출하므로, 여기서
 * deadline 초과를 감지해 {@link RegexTimeoutException} 으로 빠져나온다. 매 호출마다 nanoTime() 은
 * 비싸므로 {@link #DEADLINE_CHECK_MASK}+1(=1024) 회마다 1번만 체크한다.
 *
 * <p>시간소스는 {@link LongSupplier} 로 주입 가능하다(package-private 오버로드). production 은 항상
 * {@code System::nanoTime} 을 쓰지만, 단위 테스트가 deadline 경계(미만/정확/초과)를 {@code Thread.sleep}
 * 없이 결정적으로 검증할 수 있게 한다(EXT-006 — R14 CI JDK21 flaky 계보 차단).
 */
public final class DeadlineCharSequence implements CharSequence {

    /** nanoTime() 호출 빈도 제어 — (counter & MASK)==0 일 때만 체크(1024회마다). package-private(내부 전용). */
    static final int DEADLINE_CHECK_MASK = 0x3FF;

    private final CharSequence delegate;
    private final long deadlineNanos;
    private final LongSupplier nanoSource;
    private int checkCounter;

    /**
     * production 생성자 — 시간소스는 {@link System#nanoTime()}.
     *
     * @param delegate      감쌀 원본 문자열
     * @param deadlineNanos 절대 deadline(nanoTime 기준). 이 값을 엄격 초과(&gt;)하면 {@code charAt} 이 throw.
     */
    public DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
        this(delegate, deadlineNanos, System::nanoTime);
    }

    /** [EXT-006] 시간소스 주입 오버로드 — package-private 테스트 전용(결정적 경계 검증). */
    DeadlineCharSequence(CharSequence delegate, long deadlineNanos, LongSupplier nanoSource) {
        this.delegate = delegate;
        this.deadlineNanos = deadlineNanos;
        this.nanoSource = nanoSource;
    }

    @Override
    public char charAt(int index) {
        // 1024회마다 deadline 검사 — 엄격 초과(>)만 reject (정확 deadline 은 통과, Design §8.1 경계).
        if ((++checkCounter & DEADLINE_CHECK_MASK) == 0 && nanoSource.getAsLong() > deadlineNanos) {
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
        // 부분열도 같은 deadline + 시간소스를 공유해 backtracking 어디서든 탈출 가능.
        return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos, nanoSource);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }
}
