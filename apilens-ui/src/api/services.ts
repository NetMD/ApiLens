// [Phase H] U2 — Active services API.
//   - listServicesDetailed : GET /v1/services (응답 확장된 ServiceInfo 6 필드)
//   - deleteService        : DELETE /v1/services/{name} (D-05 비협상 / Q-02 멱등 204)
//
// queryKey 분리 근거 (architect §6.3):
//   - ['services']             : 기존 ServiceSelector — Dashboard 헤더 dropdown
//   - ['services', 'detailed'] : 신규 ActiveServices — 30초 refetch + 독립 라이프사이클
import { deleteResource, getJson } from './client';
import type { ServicesResponse } from '../types/api';

/**
 * /services 페이지 데이터 소스. 30초 refetchInterval 사용처.
 *
 * 응답 모양은 기존 listServices 와 동일하지만 ServiceInfo 인터페이스 확장으로
 * registeredAt / lastSeenAt / source / healthStatus 4 필드가 추가된 형태.
 */
export async function listServicesDetailed(signal?: AbortSignal): Promise<ServicesResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<ServicesResponse>('/v1/services', fetchOpts);
}

/**
 * services row 제거. D-05 비협상:
 *   - traces / spans / payloads 보존 (cascade 금지 — server-side 책임)
 *   - 같은 service_name trace 재수신 시 자동 재등록 (IngestService UPSERT — server-side 책임)
 *
 * Q-02 멱등: 존재하지 않는 이름이어도 204 반환 — UI 에서 별도 분기 불필요.
 */
export async function deleteService(name: string, signal?: AbortSignal): Promise<void> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  await deleteResource(`/v1/services/${encodeURIComponent(name)}`, fetchOpts);
}
