-- ApiLens R25 (Phase V, v0.7.0) — payload 본문을 저장소 전체에서 한 번만 저장 (신규 테이블 + 참조 열)
-- [Phase R25] AC-25-01-1/AC-25-01-2/AC-25-03-1 — 사용자 명시 비협상 결정(UD-1: 본문은 한 번만 저장하고
--   행은 참조만 갖는다 / 백필 금지). CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용 —
--   스키마 변경은 언제나 새 마이그레이션 파일로만 한다.
--
-- V1~V5 무수정 (Flyway checksum). CREATE TABLE / ALTER ADD COLUMN / CREATE INDEX 는 트랜잭션 가능 명령.
-- VACUUM/ANALYZE 는 여기 두지 않는다 (트랜잭션 내 실행 불가 — V3·V4 헤더와 동일 방침).
-- DEFAULT 를 주지 않는다 — 기본값을 주면 "옛 행" 과 "본문 없음" 이 구분되지 않는다.
--   옛 행 판별식은 body_hash IS NULL AND body IS NOT NULL 이다. body_hash IS NULL 만으로 세면
--   본문이 없는 정상 행(계측이 값을 못 잡은 빈 자리표)이 영원히 옛 행으로 남는다.
-- payloads.body 열은 지우지 않는다 — 이미 쌓인 행이 그 열로 읽히고, 백필은 하지 않는다.
CREATE TABLE payload_bodies (
    body_hash     TEXT PRIMARY KEY,   -- 저장되는 본문 바이트(UTF-8)의 SHA-256, 소문자 16진수 64자
    body          TEXT NOT NULL,      -- 마스킹·절단을 거친 뒤 실제로 저장되는 본문. 빈 문자열도 정상 값
    body_bytes    INTEGER NOT NULL,   -- 위 body 의 UTF-8 바이트 수. 지문 충돌 감지와 집계에 쓴다
    first_seen_at INTEGER NOT NULL    -- epoch millis, 서버 시각 (진단용 — 판정에 쓰지 않는다)
);

ALTER TABLE payloads ADD COLUMN body_hash TEXT;

-- 본문 정리가 payloads 쪽을 NOT EXISTS 로 훑는다. 이 인덱스가 없으면 본문 한 행마다 payloads 전수 스캔이 된다.
CREATE INDEX idx_payloads_body_hash ON payloads(body_hash);
