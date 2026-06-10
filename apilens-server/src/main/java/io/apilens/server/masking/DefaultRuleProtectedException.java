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
 * 409 Conflict (E-02 확정 — Design §2-B2) — default 룰 삭제 시도.
 *
 * <p>// [Phase R12] AC-B2-2 — CLAUDE.md '데이터 모델' verbatim: "default는 비활성만 가능,
 * // 삭제 불가". 사용자 명시 비협상 결정. 409 선택 근거: 무인증 도구에서 403 은 "인증 실패"
 * // 오독, 400 은 "요청 형식 오류" 오독 — 409 가 "리소스 상태/정책상 수행 불가" 정합.
 */
public class DefaultRuleProtectedException extends RuntimeException {

    public DefaultRuleProtectedException(long ruleId) {
        super("default rule cannot be deleted: " + ruleId);
    }
}
