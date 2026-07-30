// [Phase R19] 계측 분석 API 호출 함수 — BE 계약 1:1 (설계 §4.1 · §4.2).
//
//   ① POST /v1/instrument/analysis   — 서비스 하나의 계측 순위 집계 (온디맨드 · 시간 구간 필수)
//   ② POST /v1/instrument/simulation — 고른 대상을 뺐을 때의 예상 결과 (용량은 감산 · trace 는 재계산)
//
// ⚠️ **반드시 postJson 경유** — client.ts buildHeaders() 가 `Authorization: Bearer` 를 붙인다.
//    신규 /v1/** 는 화이트리스트에 추가되지 않아 역방향 default-deny 로 보호되므로, 이 경로를
//    안 타면 헤더가 안 붙어 조용히 401 이 된다 (NFR-09).
// ⚠️ 둘 다 **읽기 전용** 이다. POST 인 이유는 (a) 온디맨드 무거운 작업 전례가 전부 POST 이고
//    (b) 화면 타임아웃 옵션(timeoutMs)을 소비하는 헬퍼가 postJson 하나뿐이며
//    (c) 대상 목록(최대 500개 클래스 이름)이 URL 로는 길이 한계에 걸리기 때문이다.
import { postJson } from './client';
import type {
  AnalysisRequest,
  AnalysisResponse,
  SimulationRequest,
  SimulationResponse,
} from '../types/api';

/**
 * 화면 쪽 응답 대기 상한 (설계 §6.2 #5 확정값).
 *
 * 서버 쿼리 상한이 15초라 **서버가 먼저 끊게** 여유를 둔 값이다. 이 상한에 먼저 걸리면
 * 브라우저가 TimeoutError 로 끊고 화면은 "구간을 좁혀 보세요"(T-21) 를 보여준다.
 * 매직 넘버 금지 — 명명 상수 (maintenance 의 MAINTENANCE_TIMEOUT_MS 동형).
 */
export const ANALYSIS_TIMEOUT_MS = 25_000;

/** postJson 옵션 조립 — exactOptionalPropertyTypes 정합 (signal 은 정의된 경우만 전달). */
function buildOptions(signal?: AbortSignal): { signal?: AbortSignal; timeoutMs: number } {
  const opts: { signal?: AbortSignal; timeoutMs: number } = { timeoutMs: ANALYSIS_TIMEOUT_MS };
  if (signal) {
    opts.signal = signal;
  }
  return opts;
}

/**
 * POST /v1/instrument/analysis — 계측 순위 집계.
 *
 * 실패 코드: 400(구간·이름 규격 위반) / 401(토큰) / 409(이미 다른 분석이 도는 중) / 5xx.
 */
export async function runInstrumentAnalysis(
  request: AnalysisRequest,
  signal?: AbortSignal,
): Promise<AnalysisResponse> {
  return postJson<AnalysisRequest, AnalysisResponse>(
    '/v1/instrument/analysis',
    request,
    buildOptions(signal),
  );
}

/**
 * POST /v1/instrument/simulation — 고른 대상 제외 시뮬레이션.
 *
 * ⚠️ fromMs/toMs 는 순위 응답의 window 값을 **그대로** 되돌려 보낸다 (구간 어긋남 차단).
 * ⚠️ targets 는 언제나 클래스 이름 목록이다 (패키지 단위 입력 없음).
 */
export async function runInstrumentSimulation(
  request: SimulationRequest,
  signal?: AbortSignal,
): Promise<SimulationResponse> {
  return postJson<SimulationRequest, SimulationResponse>(
    '/v1/instrument/simulation',
    request,
    buildOptions(signal),
  );
}
