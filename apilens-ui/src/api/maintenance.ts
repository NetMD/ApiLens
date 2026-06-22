// 데이터 관리(수동 디스크 정리) API 호출 함수 — BE 계약 1:1.
//
// 운영자가 설정 페이지에서 버튼으로 디스크를 즉시 확보하기 위한 2 동작:
//   ① POST /v1/maintenance/cleanup — 보관 기간 초과분 즉시 삭제 (RetentionSection 의 nightly cleanup 과
//      동일 정책을 "지금" 1회 수동 실행).
//   ② POST /v1/maintenance/purge — 전체 trace/span/payload 삭제 (되돌릴 수 없음).
//
// 응답 = 두 엔드포인트 공통 MaintenanceResult (설계 계약 1:1). 본문 없이 POST (body 불필요)지만
// client.ts 의 postJson 헬퍼는 JSON body 직렬화를 전제하므로 빈 객체({})를 전송한다 (saveSettings 동형).
import { getJson, postJson } from './client';
import type { MaintenanceResult, MaintenanceStatusResponse } from '../types/api';

// [Phase R13] D-08 — purge 는 대량 시 동기 응답이 수 분 걸릴 수 있어 브라우저 기본 타임아웃에
// 끊길 위험이 있다. cleanup/purge 두 동기 호출에 5분 상한을 둔다(매직넘버 금지 — 명명 상수).
// 보관 1일 가정에서 실제로는 수 초~수십 초 예상이며 5분은 reader 경합 흡수용 안전 상한.
const MAINTENANCE_TIMEOUT_MS = 300_000; // 5분 — maintenance 동기 응답 대기 상한

/**
 * POST /v1/maintenance/cleanup — 보관 기간(현재 1일) 초과분 trace 즉시 삭제.
 *
 * 응답 = MaintenanceResult (deletedTraces / freedBytes / dbSizeBytes — 바이트).
 */
export async function runRetentionCleanup(): Promise<MaintenanceResult> {
  return postJson<Record<string, never>, MaintenanceResult>('/v1/maintenance/cleanup', {}, {
    timeoutMs: MAINTENANCE_TIMEOUT_MS,
  });
}

/**
 * POST /v1/maintenance/purge — 전체 trace/span/payload 삭제 (되돌릴 수 없음).
 *
 * 응답 = MaintenanceResult. 호출 전 강한 확인(2단계 확인)을 거친다 (DataManagementSection).
 */
export async function purgeAllData(): Promise<MaintenanceResult> {
  return postJson<Record<string, never>, MaintenanceResult>('/v1/maintenance/purge', {}, {
    timeoutMs: MAINTENANCE_TIMEOUT_MS,
  });
}

/**
 * [Phase K] (US-07, AC-07-1) — POST /v1/maintenance/optimize — 삭제 없이 전체 VACUUM 으로
 * 디스크 조각만 회수 (행 재구성, 파일 삭제 0 — 설계 §4.5 / R14-D06).
 *
 * 응답 = MaintenanceResult (deletedTraces=0 / freedBytes / dbSizeBytes / busy).
 *   - busy=true  : 전체락 경합(SQLITE_BUSY) 부분 회수 / 디스크 부족 거부 / SQLITE_FULL (AC-07-3/4/5).
 *   - busy=false : 정상 회수.
 * MAINTENANCE_TIMEOUT_MS 재사용 — 매직넘버 신설 금지 (설계 §5 상수표 / planner §1.1 보존).
 * cleanup/purge 동형 (빈 body POST).
 */
export async function optimizeDatabase(): Promise<MaintenanceResult> {
  return postJson<Record<string, never>, MaintenanceResult>('/v1/maintenance/optimize', {}, {
    timeoutMs: MAINTENANCE_TIMEOUT_MS,
  });
}

// [Phase R15] AC-A3-1/AC-B1-1~3 — 수신 일시정지 set 2엔드포인트 + status 조회.
// 사용자 명시 비협상 결정(D02 503+Retry-After / D03 in-memory). CLAUDE.md '아키텍처 핵심 원칙' (수신 일시정지 단일 기능).
// ⚠️ MAINTENANCE_TIMEOUT_MS 재사용 안 함 — status/pause/resume 은 즉답이라 신규 timeout 상수 0 (설계 §2.6/G-10 보존).

/**
 * [Phase R15] AC-A3-1 — GET /v1/maintenance/status — 현재 일시정지 상태 폴링(즉답, timeout 미지정 — 기본 동작).
 *
 * 응답 = MaintenanceStatusResponse ({ paused, pausedAt }). 인증 보호 경로(키 설정 시 토큰 자동 첨부 — client.ts buildHeaders).
 */
export async function getMaintenanceStatus(signal?: AbortSignal): Promise<MaintenanceStatusResponse> {
  // exactOptionalPropertyTypes 정합 — signal 은 정의된 경우만 전달(listServices 동형).
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<MaintenanceStatusResponse>('/v1/maintenance/status', fetchOpts);
}

/**
 * [Phase R15] AC-A3-1 — POST /v1/maintenance/pause — 수신 일시정지(빈 body, 즉답). 멱등.
 *
 * 응답 = MaintenanceStatusResponse. 기존 maintenance POST(cleanup/purge/optimize) 동형 빈 body 패턴.
 */
export async function pauseReceiving(): Promise<MaintenanceStatusResponse> {
  return postJson<Record<string, never>, MaintenanceStatusResponse>('/v1/maintenance/pause', {});
}

/**
 * [Phase R15] AC-A3-1 — POST /v1/maintenance/resume — 수신 재개(빈 body, 즉답). 멱등.
 *
 * 응답 = MaintenanceStatusResponse.
 */
export async function resumeReceiving(): Promise<MaintenanceStatusResponse> {
  return postJson<Record<string, never>, MaintenanceStatusResponse>('/v1/maintenance/resume', {});
}
