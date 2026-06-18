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
package io.apilens.server.retention;

/**
 * Result of a manual maintenance action (cleanup / purge), serialized 1:1 to the
 * settings-page contract:
 * <pre>{@code { "deletedTraces": 12345, "freedBytes": 53687091200, "dbSizeBytes": 41943040 }}</pre>
 *
 * <p>// freedBytes = (작업 전 page_count − 작업 후 page_count) × page_size (음수 방지 max(0,..)).
 * // dbSizeBytes = 작업 후 page_count × page_size (현재 DB 파일 논리 크기).
 * // page 측정은 컨트롤러가 cleanup/purgeAll 을 감싸 수행 — 서비스의 기존 public 계약은 보존.
 *
 * @param deletedTraces 이번 작업으로 삭제된 trace 행 수
 * @param freedBytes    회수된 바이트 수 (page_count 감소분 × page_size, 음수면 0)
 * @param dbSizeBytes   작업 후 DB 논리 크기 (page_count × page_size)
 */
public record MaintenanceResult(int deletedTraces, long freedBytes, long dbSizeBytes) {
}
