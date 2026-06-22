// 가벼운 fetch wrapper.
// - BASE_URL = '' : Vite proxy(127.0.0.1:8765) 또는 동일 호스트 정적 자원에서 동작.
// - 4xx/5xx는 ApiError throw — React Query가 onError로 받음.
// - console.log 0건 (NFR-04). catch에서 silent throw만.
import type { ApiErrorBody } from '../types/api';
import { getApiKey } from './auth';

const BASE_URL = '';

// [Phase K] (US-04, AC-04-3): "저장 후 client.ts 의 헤더 첨부 지점이 모든 요청에 Authorization: Bearer <token> 를 조건부로 단다" (US-01 보호 API 인증). 사용자 명시 비협상 결정 (R14-D02 인증 = API Key 헤더 토큰). CLAUDE.md '아키텍처 핵심 원칙' (신뢰망 단일 토큰).
//
// 설계 §2.6a / §5 상수표: 5개 헬퍼가 각자 headers 를 구성하던 구조를 buildHeaders() 단일 진입점으로 통일.
// ⚠️ 회귀 차단 (EXT-008 정신의 FE 적용): 5곳 각자 Authorization 첨부 금지 — buildHeaders() 경유로만.
//    토큰 prefix 리터럴 "Bearer " 는 ApiKeyAuthFilter(BE) 와 양측 동일 (§5 상수표). 신규 fetch 경로 토큰 누락 회귀를 구조로 차단.
function buildHeaders(base: Record<string, string>): Record<string, string> {
  const token = getApiKey(); // api/auth.ts sessionStorage — 미설정 시 null (인증 비활성 환경)
  return token !== null && token !== '' ? { ...base, Authorization: `Bearer ${token}` } : base;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody | undefined;

  constructor(status: number, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

interface RequestOptions {
  signal?: AbortSignal;
  query?: Record<string, string | number | boolean | undefined>;
  // [Phase R13] FR-B2/BL-07/D-08 — 동기 응답 타임아웃(ms). postJson 만 소비(다른 메서드 무변경).
  // 미지정 시 기존 동작(브라우저 기본 타임아웃) 유지. maintenance(purge/cleanup) 만 5분 전달.
  timeoutMs?: number;
}

/**
 * [Phase R13] postJson 전용 signal 합성 helper (FR-B2/BL-07/D-08).
 * - timeoutMs 미지정: 기존 동작 그대로(options.signal 만 사용 — 분기 미진입, 회귀 0).
 * - timeoutMs 만 지정: AbortSignal.timeout(timeoutMs) 단독.
 * - signal + timeoutMs 동시: AbortSignal.any([signal, timeout]) — 둘 중 먼저 abort 우선.
 */
function resolveSignal(options: RequestOptions): AbortSignal | undefined {
  if (options.timeoutMs === undefined) {
    return options.signal; // 기존 호출 경로 — 동작 불변
  }
  const timeoutSignal = AbortSignal.timeout(options.timeoutMs);
  if (options.signal) {
    return AbortSignal.any([options.signal, timeoutSignal]);
  }
  return timeoutSignal;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  const url = new URL(BASE_URL + path, window.location.origin);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v === undefined || v === null) continue;
      url.searchParams.set(k, String(v));
    }
  }
  // Vite proxy 라우팅을 위해 path와 query만 반환 (동일 origin이라 절대 URL 불필요).
  return url.pathname + url.search;
}

async function parseErrorBody(res: Response): Promise<ApiErrorBody | undefined> {
  try {
    const text = await res.text();
    if (!text) return undefined;
    const parsed: unknown = JSON.parse(text);
    if (
      typeof parsed === 'object' &&
      parsed !== null &&
      'error' in parsed &&
      typeof (parsed as { error: unknown }).error === 'string'
    ) {
      return parsed as ApiErrorBody;
    }
    return { error: text };
  } catch {
    // body 파싱 실패는 silent — 상위에서 status code로 판단
    return undefined;
  }
}

/**
 * 공통 GET 요청. JSON 응답 자동 parse.
 *
 * @throws {ApiError} status >= 400
 * @throws {Error} 네트워크 오류 (fetch reject)
 */
export async function getJson<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const url = buildUrl(path, options.query);
  const fetchInit: RequestInit = {
    method: 'GET',
    headers: buildHeaders({ Accept: 'application/json' }),
  };
  if (options.signal) {
    fetchInit.signal = options.signal;
  }
  const res = await fetch(url, fetchInit);

  if (!res.ok) {
    const body = await parseErrorBody(res);
    const message = body?.error ?? `HTTP ${res.status}`;
    throw new ApiError(res.status, message, body);
  }

  // 200 OK + 빈 본문은 v0.1 endpoint에 없음. 항상 JSON 기대.
  return (await res.json()) as T;
}

/**
 * [Phase H] 공통 POST 요청. JSON body 전송 + JSON 응답 parse.
 *
 * @throws {ApiError} status >= 400
 * @throws {Error} 네트워크 오류 (fetch reject)
 */
export async function postJson<TReq, TRes>(
  path: string,
  body: TReq,
  options: RequestOptions = {},
): Promise<TRes> {
  const url = buildUrl(path, options.query);
  const fetchInit: RequestInit = {
    method: 'POST',
    headers: buildHeaders({
      Accept: 'application/json',
      'Content-Type': 'application/json',
    }),
    body: JSON.stringify(body),
  };
  // [Phase R13] timeoutMs 옵션 소비(getJson/putJson/patchJson/deleteResource 는 무변경 — postJson 단독).
  const signal = resolveSignal(options);
  if (signal) {
    fetchInit.signal = signal;
  }
  const res = await fetch(url, fetchInit);

  if (!res.ok) {
    const errBody = await parseErrorBody(res);
    const message = errBody?.error ?? `HTTP ${res.status}`;
    throw new ApiError(res.status, message, errBody);
  }

  return (await res.json()) as TRes;
}

/**
 * [Phase R12] 공통 PUT 요청. JSON body 전송 + JSON 응답 parse (DG-04 — postJson 동형).
 *
 * 사용처: PUT /v1/settings (FR-B1).
 *
 * @throws {ApiError} status >= 400
 * @throws {Error} 네트워크 오류 (fetch reject)
 */
export async function putJson<TReq, TRes>(
  path: string,
  body: TReq,
  options: RequestOptions = {},
): Promise<TRes> {
  const url = buildUrl(path, options.query);
  const fetchInit: RequestInit = {
    method: 'PUT',
    headers: buildHeaders({
      Accept: 'application/json',
      'Content-Type': 'application/json',
    }),
    body: JSON.stringify(body),
  };
  if (options.signal) {
    fetchInit.signal = options.signal;
  }
  const res = await fetch(url, fetchInit);

  if (!res.ok) {
    const errBody = await parseErrorBody(res);
    const message = errBody?.error ?? `HTTP ${res.status}`;
    throw new ApiError(res.status, message, errBody);
  }

  return (await res.json()) as TRes;
}

/**
 * [Phase R12] 공통 PATCH 요청. JSON body 전송 + JSON 응답 parse (DG-04 — postJson 동형).
 *
 * 사용처: PATCH /v1/masking-rules/{id} (FR-B2 토글 — body 필드 단일명 enabled).
 *
 * @throws {ApiError} status >= 400
 * @throws {Error} 네트워크 오류 (fetch reject)
 */
export async function patchJson<TReq, TRes>(
  path: string,
  body: TReq,
  options: RequestOptions = {},
): Promise<TRes> {
  const url = buildUrl(path, options.query);
  const fetchInit: RequestInit = {
    method: 'PATCH',
    headers: buildHeaders({
      Accept: 'application/json',
      'Content-Type': 'application/json',
    }),
    body: JSON.stringify(body),
  };
  if (options.signal) {
    fetchInit.signal = options.signal;
  }
  const res = await fetch(url, fetchInit);

  if (!res.ok) {
    const errBody = await parseErrorBody(res);
    const message = errBody?.error ?? `HTTP ${res.status}`;
    throw new ApiError(res.status, message, errBody);
  }

  return (await res.json()) as TRes;
}

/**
 * [Phase H] 공통 DELETE 요청. 204 No Content 기대 — 본문 parse 안 함.
 *
 * @throws {ApiError} status >= 400
 * @throws {Error} 네트워크 오류 (fetch reject)
 */
export async function deleteResource(
  path: string,
  options: RequestOptions = {},
): Promise<void> {
  const url = buildUrl(path, options.query);
  const fetchInit: RequestInit = {
    method: 'DELETE',
    // [Phase K] (US-04, AC-04-3): deleteResource 는 기존 headers 객체가 없었으므로 buildHeaders({}) 신설 — 토큰 첨부 단일화 (설계 §2.6a, DELETE /v1/services/{name} 도 보호 경로라 토큰 필요).
    headers: buildHeaders({}),
  };
  if (options.signal) {
    fetchInit.signal = options.signal;
  }
  const res = await fetch(url, fetchInit);

  if (!res.ok) {
    const errBody = await parseErrorBody(res);
    const message = errBody?.error ?? `HTTP ${res.status}`;
    throw new ApiError(res.status, message, errBody);
  }
  // 204 No Content — 응답 본문 무시.
}
