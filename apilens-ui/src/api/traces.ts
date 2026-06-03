// ApiLens read 엔드포인트 호출 함수.
// F1 사용처: GET /v1/traces, GET /v1/services.
// F2 추가: GET /v1/traces/{id}, GET /v1/traces/{id}/spans/{spanId}/payloads.
import { ApiError, getJson } from './client';
import type {
  ListTracesParams,
  PayloadsResponse,
  ServicesResponse,
  TraceDetailResponse,
  TracesResponse,
} from '../types/api';

/**
 * 대시보드 산점도 + 리스트 데이터 소스.
 *
 * NOTE: LatencyScatter / TraceList 는 같은 queryKey로 dedupe된다 (한 번만 호출).
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
      limit: params.limit,
      cursor: params.cursor,
    },
  };
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<TracesResponse>('/v1/traces', fetchOpts);
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
