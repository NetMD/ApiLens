-- ApiLens Phase H 스키마 확장
-- [Phase H] AC-01-1 / AC-06-* — D-01~D-05 비협상 (사용자 명시 비협상 결정)
-- CLAUDE.md '데이터 모델 (5개 테이블, 변경 신중히)' 인용
-- V1 한 줄도 수정 금지 (Flyway checksum)
--
-- 2개 테이블 + setup_state 초기 row 1개.
-- services.last_seen_at 은 NULLABLE — wizard 등록 후 trace 미수신 = NULL = healthStatus 'never'.
-- R12 회귀 가드: services 에 trace_count / health_status 컬럼 추가 절대 금지 (응답 시점 aggregation).

CREATE TABLE services (
    service_name     TEXT PRIMARY KEY,                  -- traces.service_name 과 동일 의미 / UNIQUE
    registered_at    INTEGER NOT NULL,                  -- epoch millis, 첫 INSERT 시점 보존 (UPDATE 0)
    last_seen_at     INTEGER,                           -- NULL = wizard 등록 후 trace 미수신
    source           TEXT NOT NULL                      -- 'wizard' | 'auto', 첫 INSERT 시점 보존
);

CREATE INDEX idx_services_last_seen ON services(last_seen_at);

CREATE TABLE setup_state (
    id               INTEGER PRIMARY KEY,               -- 단일 row 정책 (id=1 고정)
    completed        INTEGER NOT NULL DEFAULT 0,        -- 0 = 미완료, 1 = 완료
    completed_at     INTEGER,
    server_url       TEXT
);

INSERT INTO setup_state (id, completed, completed_at, server_url)
VALUES (1, 0, NULL, NULL);
