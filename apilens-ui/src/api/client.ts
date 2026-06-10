// 가벼운 fetch wrapper.
// - BASE_URL = '' : Vite proxy(127.0.0.1:8765) 또는 동일 호스트 정적 자원에서 동작.
// - 4xx/5xx는 ApiError throw — React Query가 onError로 받음.
// - console.log 0건 (NFR-04). catch에서 silent throw만.
import type { ApiErrorBody } from '../types/api';

const BASE_URL = '';

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
    headers: { Accept: 'application/json' },
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
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
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
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
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
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
    },
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
