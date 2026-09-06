-- ApiLens R25 (Phase V, v0.7.0) — span 속성 안의 SQL 원문을 한 번만 저장 (신규 테이블)
-- [Phase R25] AC-25-02-1/AC-25-02-7 — 사용자 명시 비협상 결정(UD-2: SQL 원문도 한 번만 저장한다).
--   CLAUDE.md '데이터 모델 (8개 테이블, 변경 신중히)' 인용.
--
-- V1~V6 무수정 (Flyway checksum). CREATE TABLE 은 트랜잭션 가능 명령.
-- spans 테이블은 건드리지 않는다 — 참조는 attributes_json 안 예약 키(apilens.stmt.ref)에 둔다.
--   그래서 insertSpans 의 INSERT OR REPLACE SQL 문자열·컬럼은 이번에도 diff 0 이다.
-- ★이 표는 v0.7.0 에서 지우지 않는다 (사용자 확정). 종류 수만큼만 자라고 배포할 때만 는다.
--   다시 볼 조건: 행 수가 1,000 을 넘거나 statement 바이트 합이 10 MB 를 넘으면 정리 필요성을 재판정한다
--   (2026-09-05 15:33 실측 57종 · 43 KB).
CREATE TABLE sql_statements (
    stmt_hash     TEXT PRIMARY KEY,   -- SQL 원문(UTF-8)의 SHA-256, 소문자 16진수 64자
    statement     TEXT NOT NULL,      -- SQL 원문 그대로. 마스킹 대상이 아니다(payload 와 다른 축)
    first_seen_at INTEGER NOT NULL    -- epoch millis, 서버 시각 (진단용)
);
