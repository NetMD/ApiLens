// 데이터 관리(수동 디스크 정리) API 호출 함수 — BE 계약 1:1.
//
// 운영자가 설정 페이지에서 버튼으로 디스크를 즉시 확보하기 위한 2 동작:
//   ① POST /v1/maintenance/cleanup — 보관 기간 초과분 즉시 삭제 (RetentionSection 의 nightly cleanup 과
//      동일 정책을 "지금" 1회 수동 실행).
//   ② POST /v1/maintenance/purge — 전체 trace/span/payload 삭제 (되돌릴 수 없음).
//
// 응답 = 두 엔드포인트 공통 MaintenanceResult (설계 계약 1:1). 본문 없이 POST (body 불필요)지만
// client.ts 의 postJson 헬퍼는 JSON body 직렬화를 전제하므로 빈 객체({})를 전송한다 (saveSettings 동형).
import { postJson } from './client';
import type { MaintenanceResult } from '../types/api';

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
