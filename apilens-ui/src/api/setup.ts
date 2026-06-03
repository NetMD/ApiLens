// [Phase H] U4 / U1 — Setup wizard API.
//   - GET /v1/setup/state  → FirstRunGuard 분기
//   - POST /v1/setup/complete → wizard 완료 / skip 둘 다
//   - GET /v1/setup/agent-jar-path → [R10] AC-05-4 (D-H10-01 비협상) 신규 — server 자동 추출 절대경로
//
// 비협상 영역: services 배열 omit / [] / [{...}] 셋 다 200 (Q-01).
import { getJson, postJson } from './client';
import type {
  AgentJarPathResponse,
  SetupCompleteRequest,
  SetupCompleteResponse,
  SetupStateResponse,
} from '../types/api';

/**
 * 첫 실행 라우팅 가드용. staleTime Infinity (Q-05 — completed=true 도달 후 재호출 0).
 */
export async function getSetupState(signal?: AbortSignal): Promise<SetupStateResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<SetupStateResponse>('/v1/setup/state', fetchOpts);
}

/**
 * Wizard 완료 / skip 둘 다 동일 endpoint.
 * services=[] 빈 배열 또는 omit 시 skip 경로 (D-04 비협상).
 */
export async function completeSetup(
  body: SetupCompleteRequest,
  signal?: AbortSignal,
): Promise<SetupCompleteResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return postJson<SetupCompleteRequest, SetupCompleteResponse>(
    '/v1/setup/complete',
    body,
    fetchOpts,
  );
}

/**
 * [R10] AC-05-4 (D-H10-01 비협상) — GET /v1/setup/agent-jar-path.
 *
 * server 가 startup 시 임베드된 apilens-agent.jar 를 자동 추출한 절대경로를 반환한다.
 * path=null 도 정상 (NFR-02 silent fallback). UI 가 null 시 placeholder + 경고 안내.
 */
export async function fetchAgentJarPath(signal?: AbortSignal): Promise<AgentJarPathResponse> {
  const fetchOpts: { signal?: AbortSignal } = {};
  if (signal) {
    fetchOpts.signal = signal;
  }
  return getJson<AgentJarPathResponse>('/v1/setup/agent-jar-path', fetchOpts);
}
