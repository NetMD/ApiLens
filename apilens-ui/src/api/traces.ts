// ApiLens read 엔드포인트 호출 함수.
// F1 사용처: GET /v1/traces, GET /v1/services.
// F2 추가: GET /v1/traces/{id}, GET /v1/traces/{id}/spans/{spanId}/payloads.
// [R28] 추가: GET /v1/trace-points (대시보드 산점도의 대표 점).
import { ApiError, getJson } from './client';
import type {
  ListTracesParams,
  PayloadsResponse,
  ServicesResponse,
  TraceDetailResponse,
  TracePointsParams,
  TracePointsResponse,
  TracesResponse,
} from '../types/api';

/**
 * 대시보드 아래 trace 목록(TraceList) 데이터 소스.
 *
 * [R28/FR-28-08] 산점도는 이제 이 응답을 쓰지 않는다 — 산점도는 아래 `listTracePoints`(대표 점)를 따로 부른다.
 *   두 조회는 키가 다르다(`['traces','list',…]` · `['traces','points',…]`). 옛 머리 주석의 「같은 queryKey 로 dedupe」는
 *   이 라운드부터 거짓이라 고쳐 적었다.
 * [R28/AC-28-03-1] 검색어 `q` 를 주소에 싣는다 — 그동안 이 함수가 `q` 를 빠뜨려 검색이 서버로 가지 않았다
 *   (주소에 검색어가 있던 탭은 이제 결과가 줄어 보이는 것이 정상).
 */
export async function listTraces(
  params: ListTracesParams,
  signal?: AbortSignal,
): Promise<TracesResponse> {
  const fetchOpts: { signal?: AbortSignal; query: Record<string, string | number | boolean | undefined> } = {
    query: {
      service: params.service,
      since: params.since,
      until: params.until,
      status: params.status,
      q: params.q,
      limit: params.limit,
      cursor: params.cursor,
    },
  };
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<TracesResponse>('/v1/traces', fetchOpts);
}

/**
 * [R28/FR-28-05] 대시보드 산점도 데이터 소스 — 시간 칸마다 가장 느린 정상 trace 하나와 오류(설계 §2.1).
 *
 * 칸 크기는 서버가 창 길이로 고른다(화면은 칸 크기를 보내지 않는다). 서버가 바쁘면 503 — 부르는 쪽이
 * 오류 화면으로 바꾸지 않고 잠시 뒤 다시 부른다(본문 글은 화면에 안 보임 · UC-R28-17).
 */
export async function listTracePoints(
  params: TracePointsParams,
  signal?: AbortSignal,
): Promise<TracePointsResponse> {
  const fetchOpts: { signal?: AbortSignal; query: Record<string, string | number | boolean | undefined> } = {
    query: {
      service: params.service,
      since: params.since,
      until: params.until,
      status: params.status,
      q: params.q,
    },
  };
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<TracePointsResponse>('/v1/trace-points', fetchOpts);
}

/** 헤더 ServiceSelector 데이터 소스. */
export async function listServices(signal?: AbortSignal): Promise<ServicesResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<ServicesResponse>('/v1/services', fetchOpts);
}

/**
 * trace 미존재 (404) 전용 에러 — UI에서 풀 페이지 ErrorState 분기에 사용.
 * BL-08: BE 본문 메시지는 노출 금지. status code로만 분기.
 */
export class TraceNotFoundError extends Error {
  readonly traceId: string;
  constructor(traceId: string) {
    super('trace not found');
    this.name = 'TraceNotFoundError';
    this.traceId = traceId;
  }
}

/** GET /v1/traces/{traceId} — trace 메타 + spans 평면 배열. */
export async function fetchTraceDetail(
  traceId: string,
  signal?: AbortSignal,
): Promise<TraceDetailResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  try {
    return await getJson<TraceDetailResponse>(
      `/v1/traces/${encodeURIComponent(traceId)}`,
      fetchOpts,
    );
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      throw new TraceNotFoundError(traceId);
    }
    // 5xx / network는 그대로 (ApiError 또는 TypeError) — TanStack Query에서 잡음
    throw err;
  }
}

/** GET /v1/traces/{traceId}/spans/{spanId}/payloads — payload lazy load. */
export async function fetchPayloads(
  traceId: string,
  spanId: string,
  signal?: AbortSignal,
): Promise<PayloadsResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<PayloadsResponse>(
    `/v1/traces/${encodeURIComponent(traceId)}/spans/${encodeURIComponent(spanId)}/payloads`,
    fetchOpts,
  );
}
