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
package com.example.sampleapp;

/**
 * POST /users 요청 본문. password / ssn은 ApiLens default 마스킹 룰
 * (password 필드, 주민번호 정규식)을 검증하기 위한 필드.
 */
public record UserRequest(
        String name,
        String password,
        String ssn,
        String email
) {
}
