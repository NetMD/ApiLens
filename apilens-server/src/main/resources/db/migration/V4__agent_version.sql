-- ApiLens R19 (Phase P, v0.5.0) — services 에 agent 버전 정체성 메타 1컬럼 추가
-- [Phase R19] AC-01-1/AC-01-2/AC-01-3 — D-1/D-2 비협상. 사용자 명시 비협상 결정.
-- CLAUDE.md '데이터 모델' 인용 — V1/V2/V3 수정 금지 (Flyway checksum), 변경은 본 V4 만.
--
-- ★ V2__services_and_setup.sql:8 의 R12 회귀 가드에 대한 예외 명문:
--   그 가드 원문은 "services 에 trace_count / health_status 컬럼 추가 절대 금지 (응답 시점 aggregation)" 이며
--   지금도 유효하다. 이번에 더하는 agent_version 은 **집계로는 만들 수 없는 정체성 메타**이고,
--   같은 테이블에 이미 있는 source 컬럼(V2:14)과 성격이 같다. 집계 결과 컬럼은 이번에도 0개다.
--   ⚠️ V2 파일 자체는 주석 한 글자도 고치지 않는다 — Flyway 는 파일 전체 내용의 체크섬을 쓰므로
--      주석만 바꿔도 기존 운영 DB 가 부팅에 실패한다. 예외 명문은 이 V4 헤더가 단일 거주지다.
--
-- SQLite ALTER TABLE 은 ADD COLUMN 만 지원한다. 기존 행은 NULL 이 되며 그것이 정상이다
-- (= 이 서비스가 v0.5.0 collector 로 바뀐 뒤 아직 재시작하지 않았다는 뜻 — 유일한 해석).
-- DEFAULT 를 주지 않는다 — 기본값을 주면 "값 없음" 과 "기본값" 이 구분되지 않아 화면의 '—' 가 뜻을 잃는다.
-- 인덱스를 만들지 않는다 — 조회는 언제나 services 전건(행 수 = 서비스 수)이라 이득 0.
-- VACUUM/ANALYZE 는 여기 두지 않는다 (트랜잭션 내 실행 불가 — StartupDbInitializer 거주, V3 헤더와 동일 방침).

ALTER TABLE services ADD COLUMN agent_version TEXT;
