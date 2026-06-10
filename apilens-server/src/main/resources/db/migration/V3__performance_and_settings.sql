-- ApiLens R12 (Phase I, v0.2.0) — 성능 인덱스 + 설정 저장소
-- [Phase R12] AC-A4-1/AC-A4-2/AC-B1-1 — D-05/D-06 비협상. 사용자 명시 비협상 결정.
-- CLAUDE.md '데이터 모델' 인용 — V1/V2 수정 금지 (Flyway checksum), 변경은 본 V3 만.
-- VACUUM/ANALYZE 는 여기 두지 않는다 — VACUUM 은 트랜잭션 내 실행 불가 (StartupDbInitializer 거주, Design §2-A2).

-- FR-A4: /v1/traces 필터+정렬+keyset 흡수 + /v1/services 24h 윈도우 카운트 covering (Design §2-A3)
CREATE INDEX idx_traces_service_start ON traces(service_name, start_time DESC, trace_id DESC);

-- FR-B1: 설정 KV (v0.2 노출 키: 'retention.days' — 키 검증은 서버 SettingsRegistry 가 SSOT)
CREATE TABLE settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  INTEGER NOT NULL          -- epoch millis, 서버 시각
);
-- 시드 0행 (의도): 행 부재 = yml fallback 경로가 살아있는 분기 (D-05 "DB 값이 yml 보다 우선"의 '없으면 yml').
-- 시드를 넣으면 fallback 이 영구 데드 분기가 된다.
