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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R13] PayloadGuard 경계값 단위 테스트 (Design §7.1 TC-A1~A7).
 *
 * <p>비협상 anchor (EXT-005 verbatim 인용 — 정방향 동사):
 * <ul>
 *   <li>AC-A1-1: "body 한도 초과 시 잘라 저장 + truncated=true" (D-03 확정 게이트)</li>
 *   <li>AC-A1-2: "size_bytes = 자르기 전 원본 byte 길이" (D-04 보존 의미)</li>
 *   <li>AC-A1-5: "한도 이하 = 원본 무손실 + truncated 값 보존 (OR)" (D-03)</li>
 * </ul>
 *
 * <p>경계 입력값 (EXT-002): N−1 / N / N+1 / 멀티바이트 경계 — 4 경계 모두 케이스화.
 * agent PayloadTruncator 와 의미 동등성(NFR-01 격리하 복제)을 봉인한다.
 *
 * <p>테스트 한도 N=16 (작은 값으로 경계 직접 검증). 운영 기본은 1MB.
 */
class PayloadGuardTest {

    private static final long N = 16L;

    private static int utf8Len(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    // ─── TC-A1: ASCII 초과 (N+5) → 앞 N byte 만 저장 ──────────────────────────
    @Test
    void truncatesAsciiBodyExceedingLimitAndKeepsOriginalSize() {
        String body = "a".repeat((int) N + 5); // 21 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertTrue(r.truncated(), "AC-A1-1: 한도 초과 시 truncated=true");
        assertEquals(N, utf8Len(r.body()), "앞 N byte 만 저장");
        assertEquals(N + 5, r.sizeBytes(), "AC-A1-2: size_bytes = 자르기 전 원본 byte");
        assertEquals("a".repeat((int) N), r.body());
    }

    // ─── TC-A2: ASCII 정확히 한도 (N) → 원본 그대로 (≤ 분기, AC-A1-5) ───────────
    @Test
    void keepsBodyExactlyAtLimitUntruncated() {
        String body = "a".repeat((int) N); // 16 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertFalse(r.truncated(), "AC-A1-5: 정확히 한도면 보존 (<= 분기)");
        assertEquals(body, r.body(), "원본 그대로");
        assertEquals(N, r.sizeBytes());
    }

    // ─── TC-A3: ASCII 한도 미만 (N−1) → 원본 그대로 ───────────────────────────
    @Test
    void keepsBodyBelowLimitUntruncated() {
        String body = "a".repeat((int) N - 1); // 15 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertFalse(r.truncated());
        assertEquals(body, r.body());
        assertEquals(N - 1, r.sizeBytes());
    }

    // ─── TC-A4: ASCII 한도 직상 (N+1) → 절단 ──────────────────────────────────
    @Test
    void truncatesBodyJustAboveLimit() {
        String body = "a".repeat((int) N + 1); // 17 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertTrue(r.truncated());
        assertEquals(N, utf8Len(r.body()), "앞 N byte 만 저장");
        assertEquals(N + 1, r.sizeBytes());
    }

    // ─── TC-A5: 멀티바이트 경계 (한글 "가"=3byte) → 깨진 byte 0 ────────────────
    @Test
    void truncatesAtUtf8CharBoundaryWithoutBreakingMultibyteChar() {
        // "가" = 3 byte. 6글자 = 18 byte (> N=16). N=16 경계는 "가"(13~15) 다음 16번째 byte 중간.
        String body = "가".repeat(6); // 18 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertTrue(r.truncated());
        // walk-back: 16 byte 경계가 6번째 "가"의 중간이면 직전 완전 문자(5번째)까지 = 15 byte.
        assertTrue(utf8Len(r.body()) <= N, "byte 길이 <= N");
        // replacement char(U+FFFD) 0건 — 멀티바이트 문자를 쪼개지 않음.
        assertFalse(r.body().contains("�"), "깨진 문자(U+FFFD) 0건");
        assertEquals(18, r.sizeBytes(), "size_bytes = 원본 18 byte");
        // 저장된 본문은 온전한 "가" 의 연속 (앞 5글자 = 15 byte).
        assertEquals("가".repeat(5), r.body());
    }

    // ─── TC-A6: body=null → null 반환, truncated=false (가드 진입 전 분기) ─────
    @Test
    void returnsNullForNullBodyWithoutTruncation() {
        PayloadGuard.Result r = PayloadGuard.guard(null, N);
        assertNull(r.body());
        assertEquals(0L, r.sizeBytes());
        assertFalse(r.truncated());
    }

    // ─── 멀티바이트 정확히 경계에 떨어지는 case (쪼갬 0) ───────────────────────
    @Test
    void keepsMultibyteBodyWhenItLandsExactlyOnLimit() {
        // "가가가가가" = 15 byte (< N=16) → 보존.
        String body = "가".repeat(5); // 15 byte
        PayloadGuard.Result r = PayloadGuard.guard(body, N);
        assertFalse(r.truncated(), "15 byte < 16 → 보존");
        assertEquals(body, r.body());
        assertEquals(15L, r.sizeBytes());
    }
}
