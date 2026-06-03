-- ApiLens v0.1 schema
-- 5개 테이블: traces, spans, payloads, masking_rules, retention_meta
-- 설계 원칙:
--   - traces 테이블: trace 리스트 화면 빠른 조회용 요약
--   - spans 테이블: 노드 그래프 그릴 때 trace 단위로 쿼리
--   - payloads 테이블: 별도 분리 (큰 사이즈 처리, JOIN 없이 span 조회 빠르게)
--   - masking_rules 테이블: default(is_default=1)와 custom 모두 저장

CREATE TABLE traces (
    trace_id        TEXT PRIMARY KEY,
    root_operation  TEXT NOT NULL,
    service_name    TEXT NOT NULL,
    start_time      INTEGER NOT NULL,   -- epoch millis
    duration_ms     INTEGER NOT NULL,
    status          TEXT NOT NULL,      -- 'OK' | 'ERROR'
    span_count      INTEGER NOT NULL,
    service_count   INTEGER NOT NULL DEFAULT 1,
    has_error       INTEGER NOT NULL DEFAULT 0,
    received_at     INTEGER NOT NULL    -- 서버 수신 시각, retention 기준
);

CREATE INDEX idx_traces_start_time ON traces(start_time DESC);
CREATE INDEX idx_traces_received_at ON traces(received_at);
CREATE INDEX idx_traces_service_status ON traces(service_name, status);

CREATE TABLE spans (
    span_id         TEXT PRIMARY KEY,
    trace_id        TEXT NOT NULL,
    parent_span_id  TEXT,                -- root span은 NULL
    service_name    TEXT NOT NULL,
    operation_name  TEXT NOT NULL,
    span_kind       TEXT NOT NULL,       -- SERVER | CLIENT | INTERNAL | DB | UI_EVENT
    start_time      INTEGER NOT NULL,
    end_time        INTEGER NOT NULL,
    status          TEXT NOT NULL,       -- OK | ERROR
    attributes_json TEXT,                -- HTTP method/URL, SQL, exception 등 자유 속성

    FOREIGN KEY (trace_id) REFERENCES traces(trace_id) ON DELETE CASCADE
);

CREATE INDEX idx_spans_trace_id ON spans(trace_id);
CREATE INDEX idx_spans_parent ON spans(parent_span_id);

CREATE TABLE payloads (
    payload_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    span_id         TEXT NOT NULL,
    direction       TEXT NOT NULL,       -- 'in' | 'out'
    content_type    TEXT,                -- application/json, text/plain 등
    body            TEXT,                -- 마스킹 적용 후 본문
    size_bytes      INTEGER NOT NULL DEFAULT 0,
    truncated       INTEGER NOT NULL DEFAULT 0,  -- max 초과 시 1

    FOREIGN KEY (span_id) REFERENCES spans(span_id) ON DELETE CASCADE
);

CREATE INDEX idx_payloads_span_id ON payloads(span_id);

CREATE TABLE masking_rules (
    rule_id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    rule_type       TEXT NOT NULL,       -- 'field_name' | 'regex'
    pattern         TEXT NOT NULL,
    mask_strategy   TEXT NOT NULL,       -- 'full' | 'partial' | 'hash' | 'length_only'
    enabled         INTEGER NOT NULL DEFAULT 1,
    is_default      INTEGER NOT NULL DEFAULT 0,  -- 1: 빌트인, 삭제 불가 (비활성만 가능)
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE INDEX idx_masking_rules_enabled ON masking_rules(enabled);

-- Default 룰 시드 (한국 PII 중심)
-- 카드번호 정규식은 초기엔 단순 패턴 (luhn 검증은 v0.2)
INSERT INTO masking_rules (name, rule_type, pattern, mask_strategy, enabled, is_default, created_at, updated_at) VALUES
    ('주민번호',          'regex',      '\d{6}-?\d{7}',                                   'partial', 1, 1, strftime('%s','now')*1000, strftime('%s','now')*1000),
    ('카드번호',          'regex',      '\d{4}-?\d{4}-?\d{4}-?\d{4}',                     'partial', 1, 1, strftime('%s','now')*1000, strftime('%s','now')*1000),
    ('password',          'field_name', 'password|passwd|pwd',                            'full',    1, 1, strftime('%s','now')*1000, strftime('%s','now')*1000),
    ('token / secret',    'field_name', 'token|secret|authorization|api[_-]?key',         'full',    1, 1, strftime('%s','now')*1000, strftime('%s','now')*1000);

CREATE TABLE retention_meta (
    id              INTEGER PRIMARY KEY,
    last_cleanup_at INTEGER
);

INSERT INTO retention_meta (id, last_cleanup_at) VALUES (1, 0);
