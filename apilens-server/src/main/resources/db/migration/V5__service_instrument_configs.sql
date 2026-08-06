-- ApiLens R20 (Phase Q, v0.6.0) — 서비스별 원격 계측 config 저장 (신규 테이블)
-- V1~V4 무수정 (Flyway checksum). CREATE TABLE 은 트랜잭션 가능 명령 (NFR-05 4규칙 전건 준수).
-- 행 부재 = config 미설정 = ingest 202 응답에 instrumentConfig 필드가 실리지 않는다 (부재 허용형과 1:1).
-- 각 컬럼 NULL = 그 축 지시 없음 (agent 는 기동 -D 값 유지). 어휘는 이 4컬럼이 전부다 —
-- 어휘 확장은 스키마 변경 = 사용자 봉인 재개방 사안 (Q-U4 폐쇄를 스키마가 물리 강제).
-- services 테이블 무접촉 — V2:8 R12 회귀 가드(집계 컬럼 금지)와 애초에 만나지 않는다.
CREATE TABLE service_instrument_configs (
    service_name        TEXT PRIMARY KEY,     -- services.service_name 과 동일 의미 (FK 미선언 — 운영 PRAGMA foreign_keys=OFF)
    capture_params      INTEGER,              -- NULL=지시 없음 / 0=끄기 / 1=기동값 복귀 (Q-U5)
    capture_result_set  INTEGER,              -- 위와 동일
    require_entry_root  INTEGER,              -- NULL=지시 없음 / 1=억제 켜기(줄이는 방향) / 0=기동값 복귀
    gate_excludes       TEXT,                 -- 콤마 구분 FQN 목록 (agent -D exclude-packages 파싱 전례와 동형). NULL/빈=목록 없음
    updated_at          INTEGER NOT NULL      -- epoch millis, 서버 시각 (settings.updated_at 전례)
);
