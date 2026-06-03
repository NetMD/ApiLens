// status별 색상 매핑.
// - 시맨틱 컬러 단일 출처는 src/index.css @theme. (NFR-05)
// - STATUS_HEX는 Recharts 등 JS에서 hex가 직접 필요한 경우의 미러일 뿐.
//   index.css의 --color-status-* 와 hex가 일치해야 함.
import type { HealthStatus, TraceSummary } from '../types/api';

export type StatusColorKey = 'ok' | 'error' | 'slow';

/** 1초 초과 trace는 'slow'로 분류. */
export const SLOW_THRESHOLD_MS = 1000;

/** index.css @theme 의 --color-status-* 와 일치해야 하는 미러. */
export const STATUS_HEX: Record<StatusColorKey, string> = {
  ok:    '#888780',
  error: '#E24B4A',
  slow:  '#BA7517',
};

/**
 * trace 1건의 시맨틱 색상 키 결정.
 *
 * 우선순위: ERROR/hasError → error / 1000ms 초과 → slow / 그 외 → ok.
 * status는 'OK' | 'ERROR' (HTTP status code 아님).
 */
export function statusColorKey(
  trace: Pick<TraceSummary, 'status' | 'durationMs'> & { hasError?: boolean },
): StatusColorKey {
  if (trace.status === 'ERROR' || trace.hasError) return 'error';
  if (trace.durationMs > SLOW_THRESHOLD_MS) return 'slow';
  return 'ok';
}

/**
 * [R10] AC-06-3 (D-H10-03 비협상 — V-USER-R10-03 sign-off) — health 신호등 토큰 hex 미러.
 *
 * 기존 STATUS_HEX (trace status 3종) 와 의도 분리. index.css @theme `--color-health-*`
 * 4 토큰과 hex 일치 의무. Architect 가 brand grayscale 와 충돌한다고 자체 판단해서
 * hex 변경 시도 시 ESCALATE 의무 (Design §0.3 회귀 진원지 #5).
 *
 * 회귀 가드 grep:
 *   정방향: 4 hex 정확 4 hit (STATUS_HEX_HEALTH 영역)
 *   반대 (lock-in 금지): 이전 갈색 톤 active hex 잔존 0 hit 회귀 차단
 */
export const STATUS_HEX_HEALTH: Record<HealthStatus, string> = {
  active:   '#22C55E', // --color-health-active (green-500)
  stale:    '#F59E0B', // --color-health-stale  (amber-500)
  inactive: '#EF4444', // --color-health-inactive (red-500)
  never:    '#A8A29E', // --color-health-never  (stone-400, status-idle 와 hex 동일 / 의도 분리)
};

/**
 * [Phase H] SH-18 — 색만으로 표현 금지 (impeccable a11y).
 * dot 색 + 한글 라벨 + aria-label 3중 의무. CSS dot 만 그리지 말 것.
 */
export const STATUS_LABEL_KO: Record<HealthStatus, string> = {
  active:   '정상',
  stale:    '지연',
  inactive: '끊김',
  never:    '대기',
};
