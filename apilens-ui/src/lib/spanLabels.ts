// [Phase AB / R31] AC-R31-25/32/37/41 · DD-R31-20 — span 속성 키 글자와 노드 · 카드 · 머리글 글자 도우미의 단일 거주지.
//
// 외부 호출 구간(CLIENT span · v0.11.0)을 화면 세 자리가 같은 키로 읽게 하려고 이 파일 한 곳에 모은다.
//   - 그래프 노드 아래 줄(SpanNode) · 오른쪽 상세 카드 HTTP 묶음(SelectedSpanCard) · 상세 머리글(TraceDetail)
//   - 상대 주소 · 상대 포트 키 글자는 이 파일에만 적는다(다른 화면 파일은 import 만 · 회귀 가드 G-R31-12 · 13).
//   - 순수 함수만 둔다 — DOM · React 의존 0(시험 = src/test/spanLabels.test.ts).
//
// 줄이는 규칙(DD-R31-21 · 체크리스트 「틀 안에 머문다」 한 줄):
//   - 노드 아래 줄 = clientCallText 를 **끝에서** 자른다 · 상한 31 글자(넘으면 앞 30 + 「…」 한 글자).
//   - 노드 위 라벨(CLIENT 만) = 두 줄 × 줄마다 28 글자(첫 28자 안 마지막 빈칸에서 가르고 둘째 줄이 넘치면 27 + 「…」).
//   - 상한은 **글자 수**다. 근거 px(노드 틀 180 px · 고정폭 글자 9 → 약 5.4 px · 10 → 약 6.0 px)는 이 주석에만 두고
//     px 와 한 식에 섞지 않는다. 실제 폭은 실브라우저 스모크(AC-R31-28 · 30 · 31)가 잰다.
import type { SpanDetail } from '../types/api';

/** HTTP 메서드 · URL · 경로 템플릿 · 상태 코드 키 — SERVER · CLIENT 가 같은 이름을 쓴다(DD-R31-09). */
export const HTTP_METHOD_KEY = 'http.method';
export const HTTP_URL_KEY = 'http.url';
export const HTTP_ROUTE_KEY = 'http.route';
export const HTTP_STATUS_CODE_KEY = 'http.status_code';

/** [R31/AC-R31-41 · DD-R31-09] 외부 호출 상대 주소 · 상대 포트 키 — agent `ClientHttpSupport` 상수 · 속성 명세와 같은 글자. */
export const SERVER_ADDRESS_KEY = 'server.address';
export const SERVER_PORT_KEY = 'server.port';

/**
 * [R31/AC-R31-32 · DD-R31-23] 상세 카드 HTTP 묶음 키 차례 — 메서드 → URL → 상대 주소 → 상대 포트 → route → 상태 코드.
 * `as const` 를 붙이지 않는다(DV-U-R31-7): 붙이면 리터럴 튜플이 되어 `HTTP_KEYS.includes(임의 문자열)` 이 TS2345 로 막힌다.
 */
export const HTTP_KEYS: readonly string[] = [
  HTTP_METHOD_KEY,
  HTTP_URL_KEY,
  SERVER_ADDRESS_KEY,
  SERVER_PORT_KEY,
  HTTP_ROUTE_KEY,
  HTTP_STATUS_CODE_KEY,
];

/**
 * [R31/DD-R31-21] 노드 아래 줄(글자 크기 9) 글자 수 상한 — 31 글자 × 약 5.4 px ≈ 167 px ≤ 노드 틀 180 px.
 * 기획 UC-R31-3 판정 하한(메서드 + 빈칸 + 호스트:포트 ≤ 28 이면 통째 · 넘으면 호스트 첫 8자 이상)을 넘기는 값이다.
 */
export const CLIENT_SECONDARY_MAX_CHARS = 31;

/**
 * [R31/DD-R31-21] CLIENT 노드 위 라벨(글자 크기 10) **줄마다** 글자 수 상한 — 28 글자 × 약 6.0 px ≈ 168 px ≤ 180 px.
 * UC-R31-3 의 28 과 다른 뜻(위 라벨 줄마다)이다 — 숫자만 같다(DV-U-R31-8).
 */
export const CLIENT_LABEL_LINE_MAX_CHARS = 28;

/** 줄임 표시 — U+2026 한 글자(T-R31-02). */
export const ELLIPSIS = '…';

/** URL 앞머리 「스킴://권한」 — 스킴 대소문자 무시(DV-U-R31-9 · agent 는 소문자로 쓰지만 옛 · 다른 도구 자료를 위해). */
const SCHEME_AUTHORITY = /^[a-z][a-z0-9+.-]*:\/\/[^/]*/i;

/**
 * 호스트 · 포트 → `호스트:포트`.
 * - `:` 가 든 호스트(IPv6)는 `[ ]` 로 감싼다(이미 감싼 값은 그대로).
 * - 포트가 양의 정수(number)가 아니면 `:포트` 를 통째로 뺀다 — 키 없음 · `-1` · `0` · 문자열 모두(AC-R31-25 · BV-R31-06).
 */
export function formatHostPort(host: string, port: unknown): string {
  const hostText = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
  if (typeof port === 'number' && Number.isInteger(port) && port > 0) {
    return `${hostText}:${port}`;
  }
  return hostText;
}

/**
 * [R31/AC-R31-25 · 37 · T-R31-01 · 09] 외부 호출 한 줄 글자 — `{메서드} {호스트}:{포트}{경로}`.
 * - 경로 = `http.url` 에서 「스킴://권한」 을 걷어 낸 나머지(비어도 됨). `new URL()` 을 안 써서 던짐 0 · agent 글자 그대로
 *   (`pathname` 은 빈 경로를 `/` 로 바꾸고 퍼센트 인코딩을 다시 만질 수 있다).
 * - 메서드나 상대 주소가 없으면 undefined(옛 자료 · 다른 도구가 보낸 CLIENT → 부르는 쪽이 갈래를 버린다 · AC-R31-67 화면 쪽).
 */
export function clientCallText(attrs: Record<string, unknown>): string | undefined {
  const method = attrs[HTTP_METHOD_KEY];
  const host = attrs[SERVER_ADDRESS_KEY];
  if (typeof method !== 'string' || method === '' || typeof host !== 'string' || host === '') {
    return undefined;
  }
  const url = attrs[HTTP_URL_KEY];
  const path = typeof url === 'string' ? url.replace(SCHEME_AUTHORITY, '') : '';
  return `${method} ${formatHostPort(host, attrs[SERVER_PORT_KEY])}${path}`;
}

/** 끝 자르기 — max 글자 이하면 그대로 · 넘으면 앞 (max − 1) 글자 + `…`(결과 = 정확히 max 글자). */
export function truncateEnd(text: string, max: number): string {
  if (max < 1) return '';
  if (text.length <= max) return text;
  return text.slice(0, max - 1) + ELLIPSIS;
}

/**
 * [R31/AC-R31-31 · DD-R31-21] 두 줄 맞춤 — 줄마다 max 글자 이하 · 셋째 줄 없음.
 * - max 이하면 한 줄(둘째 = null).
 * - 첫 (max + 1) 글자 안 마지막 빈칸에서 가른다(빈칸 자리는 버림) · 빈칸이 없거나 맨 앞이면 max 에서 가른다.
 * - 둘째 줄이 max 를 넘으면 끝 자르기(`…`).
 */
export function fitTwoLines(text: string, max: number): [string, string | null] {
  if (text.length <= max) return [text, null];
  const space = text.slice(0, max + 1).lastIndexOf(' ');
  const [first, rest] =
    space > 0 ? [text.slice(0, space), text.slice(space + 1)] : [text.slice(0, max), text.slice(max)];
  return [first, rest === '' ? null : truncateEnd(rest, max)];
}

/**
 * [R31/AC-R31-37 · 68 · DD-R31-22] 상세 머리글 메인 라벨 — `TraceDetail.tsx` 에서 옮겨 왔다(글자 규칙 한 곳).
 *
 * - CLIENT 뿌리: clientCallText(메서드 · 호스트:포트 · 경로) · 못 만들면 rootOperation.
 *   (옛 갈래는 `new URL(url).pathname` 만 남겨 호스트가 떨어졌다 — 백로그 AR-R29-21 · BL-R31-11)
 * - 그 밖 종류: **옮기기 전 글자 그대로**(AC-R31-68) — `http.method` + `http.route`/`http.url`(full URL 이면 pathname) ·
 *   없으면 rootOperation.
 */
export function rootHttpDescription(
  spans: ReadonlyArray<SpanDetail>,
  rootOperation: string,
): string {
  const root = spans.find((s) => s.parentSpanId === null) ?? spans[0];
  if (!root) return rootOperation;
  if (root.spanKind === 'CLIENT') {
    return clientCallText(root.attributes) ?? rootOperation;
  }
  const method = root.attributes[HTTP_METHOD_KEY];
  const route = root.attributes[HTTP_ROUTE_KEY];
  const url = root.attributes[HTTP_URL_KEY];
  const path = typeof route === 'string' ? route : typeof url === 'string' ? url : null;
  if (typeof method === 'string' && path !== null) {
    let cleanPath = path;
    try {
      cleanPath = new URL(path).pathname;
    } catch {
      // path가 이미 pathname 형태인 경우 그대로
    }
    return `${method} ${cleanPath}`;
  }
  return rootOperation;
}
