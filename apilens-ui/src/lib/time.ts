// 시간 범위 계산 + 포맷팅 (BL-01).
// 초기 결정: 10m / 1h / 24h / 7d (기본 '10m').
// [inter-pipeline] 고밀도(초당 수십 trace) 운영에서 10m 가 과하게 넓어 산점도가 오른쪽 끝에
//   몰리는 문제 → 1m / 5m 짧은 프리셋 추가 (dogfooding 정정).

/** 대시보드 시간 범위 키. */
export type RangePreset = '1m' | '5m' | '10m' | '1h' | '24h' | '7d';

const RANGE_MS: Record<RangePreset, number> = {
  '1m':  1 * 60_000,
  '5m':  5 * 60_000,
  '10m': 10 * 60_000,
  '1h':  60 * 60 * 1_000,
  '24h': 24 * 60 * 60 * 1_000,
  '7d':  7 * 24 * 60 * 60 * 1_000,
};

/**
 * 현재 시각 기준 범위 계산.
 *
 * @param preset 범위 키
 * @param now 현재 시각 (epoch millis). 미지정 시 Date.now().
 * @returns since/until (epoch millis). until은 호출 시점 now 기준.
 */
export function computeRange(
  preset: RangePreset,
  now: number = Date.now(),
): { since: number; until: number } {
  const span = RANGE_MS[preset];
  return { since: now - span, until: now };
}

/**
 * Live 모드 슬라이딩 윈도우 vs OFF 모드 pinned 윈도우 분기.
 *
 * - Live ON  : until = now (매 호출마다 슬라이딩) → 새 trace가 즉시 윈도우에 들어옴
 * - Live OFF : until = pinnedUntil (사용자가 마지막으로 range 선택/Live OFF 토글한 시점)
 *
 * since는 둘 다 until - rangeMs(preset).
 */
export function computeWindow(opts: {
  range: RangePreset;
  live: boolean;
  pinnedUntil: number;
  now?: number;
}): { since: number; until: number } {
  const { range, live, pinnedUntil } = opts;
  const now = opts.now ?? Date.now();
  const until = live ? now : pinnedUntil;
  return { since: until - RANGE_MS[range], until };
}

/**
 * epoch millis → "HH:mm:ss" 로컬 포맷 (TraceList 표시용).
 *
 * Intl 사용 — 운영자 OS 로케일 따라감. 한국어 환경에선 24h 표기.
 */
export function formatHms(epochMs: number): string {
  const d = new Date(epochMs);
  const pad = (n: number): string => n.toString().padStart(2, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
