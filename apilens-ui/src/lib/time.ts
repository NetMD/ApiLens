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
 *
 * [R27/RP-06] Live ON 창은 `computeScrollWindow` 가 낸다 — 아래 Live ON 갈래는 생산에서 안 쓴다
 *   (Dashboard 는 Live OFF 에서만 이 함수를 부르고 `now` 에 pinnedUntil 을 넘긴다).
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

// ── [R27] 흐르는 차트 (작업 관리자식) ─────────────────────────────────────
// 전 항 epoch 밀리초. 조회 창은 요청을 **보내는 순간** F 로 정하고, 표시 창은 프레임 시계 T 로
// 같은 함수에서 계산한다 — 두 창을 한 함수가 내야 두 부등식(조회 ⊇ 표시)이 식으로 선다.

/** [R27/FR-27-04] P — Live 폴링 주기. Dashboard 의 refetchInterval 값 그대로(리터럴 → 상수). */
export const LIVE_POLL_MS = 5_000;
/**
 * [R27/RP-01] L — 표시 창 오른쪽 끝을 지금보다 이만큼 늦춘다. 사용자 확정은 「5초」 이고, 설계가
 * 폴링 타이머 재설정(응답 도착 뒤 다시 걸림)과 수집 지연을 더해 8초로 정했다(STEP 19 확인 후보 1).
 * L ≥ P + 2·δ + D 를 시험(TZ-05)이 잰다.
 */
export const SCROLL_LAG_MS = 8_000;
/** [R27/RP-01] δ 예산 — 요청→응답 한 번. L ≥ P + 2·δ + D 이어야 함 — TZ-05 가 잰다(L − P 로 계산해 정의하지 않는다 · 항등식이 되면 시험이 죽는다). */
export const FETCH_DELTA_BUDGET_MS = 750;
/** [R27/RP-01] D 예산 — agent 강제 flush(최대 1,000ms) + 여유 500. L ≥ P + 2·δ + D 이어야 함 — TZ-05 가 잰다. */
export const COLLECT_DELAY_BUDGET_MS = 1_500;

// ── [v0.8.0] Live OFF 한 칸 넘김 ──────────────────────────────────────────
// 사용자 요구(2026-09-27): Live 를 끄면 선택한 시간마다 차트가 한 칸(창 하나)씩 옆으로 부드럽게 넘어간다.
//   예) 1분 선택 → 1분 동안 멈춰 있다가 다음 1분 구간으로 0.5초 동안 밀려 넘어간다.
// 지금 창 = [pinnedUntil − 한 칸, pinnedUntil]. 다음 창 [pinnedUntil, pinnedUntil + 한 칸] 이 다 찬 뒤
// 늦게 도착하는 요청을 기다리는 여유(PAGE_SETTLE_MS)까지 지나면 넘긴다.

/** 창이 끝난 뒤 늦게 도착하는 요청을 기다리는 여유 — 수집 지연 예산의 두 배. */
export const PAGE_SETTLE_MS = 2 * COLLECT_DELAY_BUDGET_MS;
/** 한 칸 넘김 때 옆으로 미끄러지는 시간. */
export const PAGE_SLIDE_MS = 500;

/** 다음 넘김 시각(epoch ms). */
export function nextPageAt(pinnedUntil: number, range: RangePreset): number {
  return pinnedUntil + RANGE_MS[range] + PAGE_SETTLE_MS;
}

/**
 * 넘긴 뒤의 창 끝. 보통은 한 칸이다. 탭이 뒤에 있어 타이머가 늦게 돌았으면 다 찬 칸 수만큼 한 번에 건너뛴다
 * (그때는 창이 이어지지 않아 미끄러지지 않고 바로 바뀐다). 최소 한 칸.
 */
export function advancePinnedUntil(pinnedUntil: number, range: RangePreset, nowMs: number): number {
  const step = RANGE_MS[range];
  const full = Math.floor((nowMs - PAGE_SETTLE_MS - pinnedUntil) / step);
  return pinnedUntil + Math.max(1, full) * step;
}

/** 새 창이 옛 창 바로 다음 칸인가(같은 길이 · 옛 끝 = 새 시작) — 이때만 미끄러진다. */
export function isNextPage(
  prev: { since: number; until: number },
  next: { since: number; until: number },
): boolean {
  return next.since === prev.until && next.until - next.since === prev.until - prev.since;
}

/** 미끄러지는 중간 창 — 천천히 출발해 천천히 도착한다(ease-in-out). 끝나면 도착 창 그대로. */
export function slideWindow(
  from: { since: number; until: number },
  to: { since: number; until: number },
  startedAt: number,
  nowMs: number,
  durationMs: number = PAGE_SLIDE_MS,
): { viewSince: number; viewUntil: number } {
  const p = durationMs <= 0 ? 1 : Math.min(1, Math.max(0, (nowMs - startedAt) / durationMs));
  const e = p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
  if (p >= 1) return { viewSince: to.since, viewUntil: to.until };
  return {
    viewSince: from.since + (to.since - from.since) * e,
    viewUntil: from.until + (to.until - from.until) * e,
  };
}

/** 프레임 시계로 흐르는 범위 간격(ms) — 눈금 간격을 겸한다. 그 밖 범위는 응답 때만 옮긴다. */
const CLOCK_TICK_MS: Partial<Record<RangePreset, number>> = {
  '1m': 10_000,
  '5m': 60_000,
  '10m': 120_000,
};

/** [R27/FR-27-05] 1m·5m·10m 만 프레임 시계로 흐른다(OQ-5). */
export function isClockRange(r: RangePreset): boolean {
  return CLOCK_TICK_MS[r] !== undefined;
}

/**
 * [R27/UXD-16] 다시 그리기 걸음(ms). 5m 150 · 10m 300 = RANGE_MS/2000(폭 약 1,000px 에서 약 0.5px).
 * ★1m 의 0 은 식 밖 예외다(식대로면 30) — fps 판정 창이라 매 프레임 다시 그린다(RP-06).
 */
export function scrollStepMs(r: RangePreset): number {
  if (r === '5m' || r === '10m') return RANGE_MS[r] / 2_000;
  return 0;
}

/**
 * [R27/FR-27-04] 조회 창과 표시 창을 한 번에 낸다.
 *
 * - `querySince = F − R − L` · `queryUntil = F` (F = requestedAt = 요청을 보낸 시각)
 * - `viewUntil = min(max(T, F) − L, F)` · `viewSince = viewUntil − R` (T = nowMs)
 *
 * 두 부등식 `querySince ≤ viewSince` · `viewUntil ≤ queryUntil` 은 **같은 R 로 조회한 응답**에 대해
 * 모든 T 에서 식으로 성립한다. `F ≤ T ≤ F + L` 이면 `viewUntil = T − L` 이라 창이 멈추지 않는다.
 * 응답이 늦어 T 가 F + L 을 넘으면 창은 조회 창 끝에 선다(ST-13 · 표시만 멈춤 — 안전한 쪽).
 */
export function computeScrollWindow(o: {
  range: RangePreset;
  requestedAt: number;
  nowMs: number;
  lagMs: number;
}): { querySince: number; queryUntil: number; viewSince: number; viewUntil: number } {
  const span = RANGE_MS[o.range];
  const f = o.requestedAt;
  // 호출자가 T < F 를 넘겨도 왼쪽 부등식이 서도록 함수 안에서 max 를 한다.
  const t = Math.max(o.nowMs, f);
  const viewUntil = Math.min(t - o.lagMs, f);
  return {
    querySince: f - span - o.lagMs,
    queryUntil: f,
    viewSince: viewUntil - span,
    viewUntil,
  };
}

/**
 * [R27/UXD-02] 표시 창 안의 눈금 — 간격 배수 전부(양 끝 포함). epoch 배수라 글자가 :10 · :20 으로 떨어진다.
 * 1m·5m·10m 가 아니면 빈 배열(부르는 쪽이 recharts 기본 눈금을 쓴다).
 */
export function computeTimeTicks(viewSince: number, viewUntil: number, range: RangePreset): number[] {
  const step = CLOCK_TICK_MS[range];
  if (step === undefined || viewUntil < viewSince) return [];
  const ticks: number[] = [];
  for (let t = Math.ceil(viewSince / step) * step; t <= viewUntil; t += step) {
    ticks.push(t);
  }
  return ticks;
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

const HOUR_MS = 60 * 60 * 1_000;
/**
 * [v0.8.0] Live OFF 차트의 눈금 간격(ms) — 한 칸 넘길 때 눈금이 종이와 함께 흘러가도록 정해진 시각에 붙인다.
 * 1m·5m·10m 는 흐르는 차트와 같은 간격이다.
 */
const PAGE_TICK_MS: Record<RangePreset, number> = {
  '1m': 10_000,
  '5m': 60_000,
  '10m': 120_000,
  '1h': 10 * 60_000,
  '24h': 4 * 60 * 60_000,
  '7d': 24 * 60 * 60_000,
};

/**
 * [v0.8.0] Live OFF 차트 눈금 — 간격의 배수 시각 전부(양 끝 포함). 간격이 한 시간을 넘으면 사용자 시간대의
 * 자정에 맞춘다(24시간 창은 00·04·08시, 7일 창은 날마다 0시).
 */
export function computePageTicks(viewSince: number, viewUntil: number, range: RangePreset): number[] {
  const step = PAGE_TICK_MS[range];
  if (!(viewUntil >= viewSince)) return [];
  const ticks: number[] = [];
  if (step <= HOUR_MS) {
    for (let t = Math.ceil(viewSince / step) * step; t <= viewUntil; t += step) ticks.push(t);
    return ticks;
  }
  // 현지 자정 기준으로 맞춘다 — 시간대 차이(분)를 빼고 배수를 구한 뒤 되돌린다.
  const offset = (ms: number): number => new Date(ms).getTimezoneOffset() * 60_000;
  const firstLocal = Math.ceil((viewSince - offset(viewSince)) / step) * step;
  for (let local = firstLocal; ; local += step) {
    const t = local + offset(local);
    if (t > viewUntil) break;
    if (t >= viewSince) ticks.push(t);
  }
  return ticks;
}

/** [v0.8.0] Live OFF 차트 눈금 글자 — 7일 창은 날짜, 1시간·24시간 창은 시:분, 그 밖은 시:분:초. */
export function formatPageTick(epochMs: number, range: RangePreset): string {
  const d = new Date(epochMs);
  const pad = (n: number): string => n.toString().padStart(2, '0');
  if (range === '7d') return `${d.getMonth() + 1}/${d.getDate()}`;
  if (range === '1h' || range === '24h') return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  return formatHms(epochMs);
}


/**
 * [Phase R19] 계측 분석 구간 표기 (T-15).
 *
 * `2026-07-30 00:40 ~ 01:40 (약 1시간) · 01:40 조회` 형태.
 * 정량 수치를 보여주는 화면은 **구간과 조회 시각을 반드시 함께** 적는다 — 언제 어느 구간을 잰
 * 값인지 없으면 그 숫자가 일반화 단정으로 읽힌다.
 *
 * 세 값 모두 서버 응답의 window 값을 그대로 쓴다 (화면이 시각을 다시 계산하지 않는다).
 */
export function formatAnalysisWindow(fromMs: number, toMs: number, queriedAtMs: number): string {
  const pad = (n: number): string => n.toString().padStart(2, '0');
  const hm = (epochMs: number): string => {
    const d = new Date(epochMs);
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  const from = new Date(fromMs);
  const date = `${from.getFullYear()}-${pad(from.getMonth() + 1)}-${pad(from.getDate())}`;
  const hours = Math.round((toMs - fromMs) / HOUR_MS);
  return `${date} ${hm(fromMs)} ~ ${hm(toMs)} (약 ${hours}시간) · ${hm(queriedAtMs)} 조회`;
}
