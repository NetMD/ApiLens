// trace operationName / duration / JSON / payload body 포맷 헬퍼 모음.
// 모두 순수 함수 — vitest로 단위 테스트(src/test/format.test.ts).
//
// F2에서 추가:
// - formatDuration : ms → 사람이 읽기 좋은 표현 (0ms / 1.0s / 1m 5s 등)
// - formatJsonPretty : JSON 문자열 → 2-space pretty (parse 실패 시 원본)
// - truncateBody : payload body 길이 제한 + "더 보기" 토글 입력

/**
 * "com.example.sampleapp.UserController#create" → "UserController#create".
 * `#` 가 없거나 simple name인 경우 원본 그대로 반환. 풀 경로는 title attribute로 호버 노출.
 */
export function shortenOperation(op: string): string {
  const hashIdx = op.indexOf('#');
  if (hashIdx === -1) return op;
  const className = op.substring(0, hashIdx);
  const method = op.substring(hashIdx);
  const lastDot = className.lastIndexOf('.');
  if (lastDot === -1) return op;
  return className.substring(lastDot + 1) + method;
}

/**
 * 밀리초를 사람이 읽기 좋은 문자열로 포맷.
 * - 음수 / NaN / Infinity → "0ms" fallback
 * - <1000ms → "{n}ms" (정수 반올림)
 * - <60000ms → "{n.n}s" (소수 1자리)
 * - >=60000ms → "{m}m {s}s" (정수 초)
 */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '0ms';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const totalSec = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSec / 60);
  const seconds = totalSec % 60;
  return `${minutes}m ${seconds}s`;
}

/**
 * JSON 문자열을 2-space pretty print.
 * parse 실패 시 원본 그대로. 빈 문자열은 빈 문자열.
 */
export function formatJsonPretty(s: string): string {
  if (s === '') return '';
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

/**
 * 바이트 수를 사람이 읽기 좋은 문자열로 포맷 (디스크 용량 표시용).
 * - 음수 / NaN / Infinity → "0 B" fallback
 * - 1024 미만 → "{n} B"
 * - 이후 KB / MB / GB / TB 로 1024 단위 환산, 소수 1자리 (정수면 소수 생략)
 *
 * 예: 0 → "0 B", 1536 → "1.5 KB", 53687091200 → "50 GB", 41943040 → "40 MB"
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '0 B';
  if (bytes < 1024) return `${Math.round(bytes)} B`;
  const units = ['KB', 'MB', 'GB', 'TB'] as const;
  let value = bytes / 1024;
  let unitIdx = 0;
  while (value >= 1024 && unitIdx < units.length - 1) {
    value /= 1024;
    unitIdx += 1;
  }
  // 정수면 소수점 생략 (50 GB), 아니면 소수 1자리 (1.5 KB)
  const rounded = Math.round(value * 10) / 10;
  const text = Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
  return `${text} ${units[unitIdx]}`;
}

/**
 * body 길이가 max 초과 시 잘라낸 부분과 잘림 여부 반환.
 * UTF-16 length 기준 — 한글/이모지의 실제 byte와 다를 수 있으나, UI 가독성 차단이 목적.
 *
 * @param max 기본 5120 (≈5KB)
 */
export function truncateBody(
  body: string,
  max: number = 5120,
): { display: string; truncated: boolean } {
  if (body.length <= max) return { display: body, truncated: false };
  return { display: body.slice(0, max), truncated: true };
}
