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

/**
 * On-demand instrumentation analysis ({@code POST /v1/instrument/**}).
 *
 * <p>운영자가 "무엇이 얼마나 쌓이고 있는가" 를 서비스 하나에 대해 직접 실행해서 보고,
 * "이걸 빼면 어떻게 되는가" 를 미리 재 보는 화면의 서버 쪽이다. 두 endpoint 모두
 * <b>읽기 전용</b>이다 — DB 를 한 줄도 쓰지 않는다.
 *
 * <p>이 패키지의 비협상 규약 3가지:
 * <ul>
 *   <li><b>모든 집계 SQL 은 {@code traces} 를 FROM 첫 테이블로 쓴다.</b> FROM 절이 {@code spans}
 *       로 시작하는 SQL 이 이 패키지에 하나도 없어야 한다(NFR-01). 그래야 비용이 테이블 크기가
 *       아니라 시간 구간 안 내용에 비례하고, {@code idx_traces_service_start}
 *       ({@code V3__performance_and_settings.sql:7})가 커버링으로 걸린다.</li>
 *   <li><b>절감({@code savings})과 부작용({@code impact})은 한 응답에 함께 담는다.</b> 절감만
 *       담긴 응답을 만드는 코드 경로가 아예 없다(S-3 을 문장이 아니라 계약으로 못 박음).</li>
 *   <li><b>불확실을 "가능" 으로 반올림하지 않는다.</b> 제외 가능성 판별의 안전 방향은 언제나
 *       {@code UNKNOWN} 이다({@link io.apilens.server.instrument.ExcludeClassifier}).</li>
 * </ul>
 *
 * <p>인증은 {@code io.apilens.server.auth.AuthWhitelist} 의 역방향 default-deny 를 그대로
 * 계승한다 — 신규 {@code /v1/**} 는 화이트리스트를 <b>건드리지 않아야</b> 자동으로 보호된다.
 */
package io.apilens.server.instrument;
