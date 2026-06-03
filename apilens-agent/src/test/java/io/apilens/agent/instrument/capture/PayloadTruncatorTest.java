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
package io.apilens.agent.instrument.capture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadTruncatorTest {

    @Test
    void nullBodyReturnsZeroSize() {
        PayloadTruncator.Result r = PayloadTruncator.truncate(null, 100);

        assertNull(r.body());
        assertEquals(0, r.sizeBytes());
        assertFalse(r.truncated());
    }

    @Test
    void shortBodyPassesThroughUntouched() {
        PayloadTruncator.Result r = PayloadTruncator.truncate("hello", 100);

        assertEquals("hello", r.body());
        assertEquals(5, r.sizeBytes());
        assertFalse(r.truncated());
    }

    @Test
    void exactlyMaxBytesNotTruncated() {
        String body = "abcde"; // 5 bytes
        PayloadTruncator.Result r = PayloadTruncator.truncate(body, 5);

        assertEquals("abcde", r.body());
        assertFalse(r.truncated());
    }

    @Test
    void bodyOverLimitGetsCutAndFlagged() {
        String body = "abcdefghij"; // 10 bytes
        PayloadTruncator.Result r = PayloadTruncator.truncate(body, 4);

        assertTrue(r.truncated());
        assertEquals(10, r.sizeBytes(), "sizeBytes preserves original length");
        assertEquals(4, r.body().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("abcd", r.body());
    }

    @Test
    void utf8MultibyteCharsNeverSplit() {
        // 한글 1자 = UTF-8 3 bytes. 4자 = 12 bytes.
        String body = "한국어테스트";
        // limit 7 bytes: should yield 2 full chars (6 bytes), not partial 3rd
        PayloadTruncator.Result r = PayloadTruncator.truncate(body, 7);

        assertTrue(r.truncated());
        assertEquals("한국", r.body(), "must cut on UTF-8 char boundary, not mid-codepoint");
        assertEquals(6, r.body().getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void asciiAtBoundaryHandledCorrectly() {
        String body = "1234567890";
        PayloadTruncator.Result r = PayloadTruncator.truncate(body, 6);

        assertEquals("123456", r.body());
        assertTrue(r.truncated());
    }
}
