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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apilens.common.MaskingEngine;
import io.apilens.common.MaskingRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-reloadable holder of the shared {@link MaskingEngine}.
 *
 * <p>// [Phase R12] AC-B2-3 — D-02 비협상: "설정 페이지에 마스킹 룰 관리 UI 포함 — 결재용
 * // 신뢰 도구". 사용자 명시 비협상 결정. 핫 리로드는 **룰 세트 재로드(인스턴스 교체)만** —
 * // apilens-common MaskingEngine 코드 diff 0 (NFR-03). 엔진은 불변 객체 그대로.
 * // CLAUDE.md '아키텍처 핵심 원칙' verbatim: "마스킹은 apilens-common 의 공유 엔진 —
 * // agent 와 server 가 같은 엔진 사용. 결과 일관성 필수".
 *
 * <p>적용 시점: reload 는 **이후 ingest 분부터** 반영 (BL-06) — 기존 저장 payload 재마스킹
 * 경로 없음 ({@code insertPayload} 저장 전 1회 적용 구조 그대로).
 *
 * <p>빈 등록은 {@link MaskingConfig} (startup 1회 reload — Design §3.1.0).
 */
public class MaskingEngineHolder {

    private static final Logger log = LoggerFactory.getLogger(MaskingEngineHolder.class);

    private final MaskingRuleRepository repository;
    private final ObjectMapper mapper;
    private final AtomicReference<MaskingEngine> ref = new AtomicReference<>();

    public MaskingEngineHolder(MaskingRuleRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 현재 엔진 — ingest hot path 가 매 호출 시점 최신 인스턴스를 읽는다. */
    public MaskingEngine current() {
        return ref.get();
    }

    /**
     * 단일 재빌드 진입점 — 룰 mutation(POST/PATCH/DELETE) 성공 직후 + startup 1회 호출.
     * EXT-008: MaskingEngine 생성 호출 허용 위치 1/2 (2/2 는 MaskingPreviewService).
     *
     * <p>실패 분기: invalid regex 의 DB 유입은 POST 400 검증(Pattern.compile)이 1차 차단하지만,
     * 수동 DB 편집 등에 방어적으로 — runtime reload 실패는 기존 엔진 유지 + error 로그
     * (ingest 가용성 우선, fail-safe). startup 시점(기존 엔진 없음) 실패는 예외 전파로
     * **기동 차단** (fail-closed) — 마스킹 전무 상태(PII 평문 저장)로 기동하지 않는다.
     *
     * @throws IllegalStateException startup 로드 실패 시 — 기동 차단 (SEC-R12-02 fail-closed)
     */
    public synchronized void reload() {
        try {
            List<MaskingRule> rules = repository.findEnabled();
            ref.set(new MaskingEngine(rules, mapper)); // allow: 단일 reload 진입점 (EXT-008)
        } catch (Exception e) {
            if (ref.get() == null) {
                // [Phase R12] SEC-R12-02 — 사용자 결정(2026-06-11), fail-closed: startup 로드 실패 시
                // 빈 룰 엔진 폴백(fail-open — 마스킹 전무 상태로 PII 평문 저장) 대신 예외 전파로 기동 차단.
                // runtime reload 실패는 현행 fail-safe(기존 엔진 유지 — 아래 분기) 그대로.
                // 출처: R12 security §2 SEC-R12-02 (b)안 채택 — "startup DB 실패는 어차피 Flyway 단계에서
                // 대부분 기동 불가라 가용성 손실 미미".
                log.error("masking rule load failed at startup — blocking startup (fail-closed)", e);
                throw new IllegalStateException(
                        "masking rule load failed at startup — refusing to start without masking rules", e);
            } else {
                log.error("masking rule reload failed — keeping previous engine", e);
            }
        }
    }
}
