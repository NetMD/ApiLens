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

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [Phase R18] AC-06-1 — {@link DeadlineCharSequence} 승격(apilens-common) 후 deadline 경계 단위 테스트.
 *
 * <p>★EXT-006 결정적 테스트★: 시간소스({@link LongSupplier})를 package-private 오버로드로 주입해
 * deadline 경계(미만/정확/초과)를 {@code Thread.sleep} 없이 결정적으로 검증한다. 실제 catastrophic
 * backtracking 의 폭발 여부(JDK/CPU 의존, R14 CI JDK21 flaky 93b015a 계보)에 무의존.
 *
 * <p>체크 주기: {@code (++checkCounter & DEADLINE_CHECK_MASK)==0} 일 때만 nanoSource 를 읽는다 —
 * 즉 charAt 를 (MASK+1)=1024 회 호출해야 첫 체크가 발동한다. 아래 테스트는 delegate 길이를 2000 으로
 * 잡아 charAt 1024 회 호출 시점의 경계 동작을 결정적으로 관찰한다.
 */
class DeadlineCharSequenceTest {

    private static final int CHECK_PERIOD = DeadlineCharSequence.DEADLINE_CHECK_MASK + 1; // 1024

    /** deadline 초과(nano > deadline) → 1024번째 charAt 체크에서 RegexTimeoutException. */
    @Test
    void charAtThrowsWhenNanoExceedsDeadlineAtCheckBoundary() {
        LongSupplier overDeadline = () -> 200L; // 체크 시점 nano
        DeadlineCharSequence guarded =
                new DeadlineCharSequence("a".repeat(2000), 100L, overDeadline);

        assertThrows(RegexTimeoutException.class, () -> {
            for (int i = 0; i < CHECK_PERIOD; i++) { // i=1023 (1024번째 호출)에서 체크 발동
                guarded.charAt(i);
            }
        });
    }

    /** 정확 deadline(nano == deadline) → 엄격 초과(>)가 아니므로 통과(throw 안 함). */
    @Test
    void charAtDoesNotThrowWhenNanoExactlyAtDeadline() {
        LongSupplier atDeadline = () -> 100L;
        DeadlineCharSequence guarded =
                new DeadlineCharSequence("a".repeat(2000), 100L, atDeadline);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < CHECK_PERIOD; i++) {
                guarded.charAt(i);
            }
        });
    }

    /** deadline 미만(nano < deadline) → 통과. */
    @Test
    void charAtDoesNotThrowWhenNanoBelowDeadline() {
        LongSupplier belowDeadline = () -> 50L;
        DeadlineCharSequence guarded =
                new DeadlineCharSequence("a".repeat(2000), 100L, belowDeadline);

        assertDoesNotThrow(() -> {
            for (int i = 0; i < CHECK_PERIOD; i++) {
                guarded.charAt(i);
            }
        });
    }

    /** 짧은 입력(charAt 1024회 미만) → 체크 자체가 발동 안 함 → 이미 지난 deadline 이어도 통과(짧으면 ReDoS 아님). */
    @Test
    void shortInputNeverChecksDeadlineEvenWhenPast() {
        LongSupplier wayPast = () -> Long.MAX_VALUE;
        DeadlineCharSequence guarded = new DeadlineCharSequence("aaaa", 0L, wayPast);

        assertDoesNotThrow(() -> {
            assertEquals('a', guarded.charAt(0));
            assertEquals('a', guarded.charAt(1));
            assertEquals('a', guarded.charAt(2));
            assertEquals('a', guarded.charAt(3));
        });
        assertEquals(4, guarded.length());
    }

    /** subSequence 는 같은 deadline + 시간소스를 전파해 부분열 어디서든 탈출 가능. */
    @Test
    void subSequencePropagatesDeadlineAndNanoSource() {
        LongSupplier overDeadline = () -> 200L;
        DeadlineCharSequence guarded =
                new DeadlineCharSequence("a".repeat(2000), 100L, overDeadline);

        CharSequence sub = guarded.subSequence(0, 2000);
        assertThrows(RegexTimeoutException.class, () -> {
            for (int i = 0; i < CHECK_PERIOD; i++) {
                sub.charAt(i);
            }
        });
    }

    /** public 2-arg 생성자(승격 후 공개) — System::nanoTime 위임 + 짧은 입력 정상 위임 확인. */
    @Test
    void publicConstructorDelegatesToSystemNanoTime() {
        long future = System.nanoTime() + 10_000_000_000L; // +10s
        DeadlineCharSequence guarded = new DeadlineCharSequence("aaaa", future);

        assertEquals('a', guarded.charAt(0));
        assertEquals(4, guarded.length());
        assertEquals("aaaa", guarded.toString());
    }

    /** RegexTimeoutException 은 public 싱글턴(호출부 catch 가능) — INSTANCE 동일성 확인. */
    @Test
    void regexTimeoutExceptionIsPublicSingleton() {
        assertNotNull(RegexTimeoutException.INSTANCE);
        assertSame(RegexTimeoutException.INSTANCE, RegexTimeoutException.INSTANCE);
    }
}
