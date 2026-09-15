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
 * [R26/AC-R26-42] 숫자와 단위 사이에만 쓰는 줄바꿈 없는 공백(U+00A0).
 *
 * 좁은 폭에서 "50 GB" 가 "50" / "GB" 로 갈려 두 줄에 걸치는 것을 막는다. 눈에 안 보이는
 * 글자라 실수로 지워지기 쉬워서 리터럴 대신 이스케이프 상수 한 곳에 둔다 — 이 파일이
 * 표시 문자열을 만드는 단일 거주지이므로 상수도 여기 산다.
 *
 * ⚠️ 낱말과 낱말 사이(예: formatDuration 의 "1m 5s")에는 쓰지 않는다. 그 공백은 갈려도
 *    되는 자리이고, 여기를 넓히면 줄바꿈이 필요한 곳까지 붙어 버린다.
 */
const NON_BREAKING_SPACE = ' ';

/**
 * 바이트 수를 사람이 읽기 좋은 문자열로 포맷 (디스크 용량 표시용).
 * - 음수 / NaN / Infinity → "0 B" fallback
 * - 1024 미만 → "{n} B"
 * - 이후 KB / MB / GB / TB 로 1024 단위 환산, 소수 1자리 (정수면 소수 생략)
 *
 * 예: 0 → "0 B", 1536 → "1.5 KB", 53687091200 → "50 GB", 41943040 → "40 MB"
 * (위 예시의 공백은 모두 NON_BREAKING_SPACE 다 — 화면에서는 보통 공백과 똑같이 보인다.)
 *
 * [R26/AC-R26-42] 숫자와 단위를 잇는 공백 세 자리(fallback · 바이트 · 환산)를 모두
 * 줄바꿈 없는 공백으로 바꿨다. 환산 값만 바꾸면 "512 B" 가 여전히 갈린다.
 */
export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return `0${NON_BREAKING_SPACE}B`;
  if (bytes < 1024) return `${Math.round(bytes)}${NON_BREAKING_SPACE}B`;
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
  return `${text}${NON_BREAKING_SPACE}${units[unitIdx]}`;
}

/**
 * [Phase R19] FQCN → 단순 클래스 이름. `com.acme.batch.OrderSyncJob` → `OrderSyncJob`.
 * 패키지가 없으면 원본 그대로. (shortenOperation 과 같은 갈래의 순수 함수 — 여기 모아 둔다.)
 */
export function classSimpleName(className: string): string {
  const lastDot = className.lastIndexOf('.');
  return lastDot === -1 ? className : className.substring(lastDot + 1);
}

/**
 * [Phase R19] FQCN → 패키지 경로. `com.acme.batch.OrderSyncJob` → `com.acme.batch`.
 * 패키지가 없으면 빈 문자열.
 *
 * 이 값은 화면 표시와 "[이 패키지 전체]" 단축 선택에만 쓴다 — **서버로 보내는 값이 아니다.**
 * 서버로 가는 대상은 언제나 클래스 이름 목록이라 패키지 평균이 계산될 경로가 없다.
 */
export function classPackage(className: string): string {
  const lastDot = className.lastIndexOf('.');
  return lastDot === -1 ? '' : className.substring(0, lastDot);
}

/**
 * [Phase R19] 0.0~1.0 비율 → 사람이 읽는 백분율 문자열 (BL-19 — 단위 변환 단일 지점).
 *
 * ⚠️ **100 을 곱하는 자리는 이 함수 하나뿐이다.** 임계 판정(0.50 / 0.80)은 언제나 실수
 *    도메인에서 하고, 화면에 보일 때만 이 함수를 거친다. 다른 곳에서 손으로 `* 100` 을 쓰면
 *    비교식이 100배 틀리는 회귀가 다시 열린다.
 * - 음수 / NaN / Infinity → "0%" fallback
 * - 소수점 없이 반올림 (예: 0.834 → "83%", 0.197 → "20%")
 */
export function formatRatioPercent(ratio: number): string {
  if (!Number.isFinite(ratio) || ratio < 0) return '0%';
  return `${Math.round(ratio * 100)}%`;
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
