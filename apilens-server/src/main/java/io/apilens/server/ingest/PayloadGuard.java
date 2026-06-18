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

import java.nio.charset.StandardCharsets;

/**
 * Server-side payload size guard, mirroring the semantics of the agent's
 * {@code io.apilens.agent.instrument.capture.PayloadTruncator}.
 *
 * <p>// [Phase R13] AC-A1-1/AC-A1-2 — D-03 truncate(잘라 저장 + truncated=true) + NFR-01(agent 무변경).
 * // 사용자 명시 비협상 결정. CLAUDE.md 'Build 설정 lessons §1 (shadow jar relocate 함정)' 인용.
 * //
 * // ⚠️ agent PayloadTruncator 를 import 하지 않는다. agent 는 shadow jar 로 packaging 되며
 * // Jackson 등 외부 의존성을 io.apilens.agent.shaded.* 로 relocate 한다 — server 가 agent 클래스를
 * // import 하면 relocate 충돌(NoSuchMethodError) 위험(CLAUDE.md Build lessons §1). 로직만 동등 복제(13줄).
 * // NFR-01(agent 모듈 diff 0)도 이 격리로 자동 충족된다.
 *
 * <p>과거 P0(agent 가 호스트 mp4/ResultSet 을 0바이트로 파괴)의 truncate 와는 무관하다 — 이쪽은
 * server 저장 row 의 크기 가드(저장 시 한도까지 잘라 보존)이지 호스트 데이터 파괴가 아니다 (Design §0.2).
 *
 * <p>한도 초과 시 UTF-8 char boundary 까지 walk-back 해 멀티바이트 문자를 쪼개지 않는다.
 */
final class PayloadGuard {

    private PayloadGuard() {
    }

    /**
     * Guards the (already masked) body to {@code maxBytes}. Returns the original body
     * unchanged ({@code truncated=false}) when it is within the limit.
     *
     * @param maskedBody mask 결과 문자열 (NFR-06: mask → guard 순서 — 마스킹 회피 차단)
     * @param maxBytes   개별 payload body 최대 저장 byte
     */
    static Result guard(String maskedBody, long maxBytes) {
        if (maskedBody == null) {
            return new Result(null, 0L, false);
        }
        byte[] bytes = maskedBody.getBytes(StandardCharsets.UTF_8);
        long originalSize = bytes.length;
        if (bytes.length <= maxBytes) {
            return new Result(maskedBody, originalSize, false);
        }
        // Walk back to a UTF-8 char boundary so we don't slice a multi-byte sequence (agent 동등).
        // maxBytes <= bytes.length 이 보장된 분기이므로 (int) Math.min(..) 은 항상 유효 인덱스 범위.
        int cut = (int) Math.min(maxBytes, bytes.length);
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new Result(new String(bytes, 0, cut, StandardCharsets.UTF_8), originalSize, true);
    }

    /**
     * Guard result.
     *
     * @param body       (possibly truncated) body to store
     * @param sizeBytes  byte length of the masked body before truncation (자르기 전 원본 크기 의미)
     * @param truncated  whether the guard truncated the body
     */
    record Result(String body, long sizeBytes, boolean truncated) {
    }
}
