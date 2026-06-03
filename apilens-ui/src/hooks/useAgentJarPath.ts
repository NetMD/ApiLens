// [R10] AC-05-8 (D-H10-01 비협상 — FE 캐시 정책).
//
// server startup 시 1회 추출이므로 staleTime: Infinity. retry: 1 (일시 네트워크 실패만).
// queryKey 정적 ['agent', 'jar-path'] — SH-13 정합 (시간 변수 박지 말 것).
// path=null 응답도 정상 (NFR-02 fallback 분기).
//
// 회귀 가드 grep:
//   정방향: staleTime: Infinity 정확 1 hit / queryKey: ['agent', 'jar-path'] 정확 1 hit
//   반대 (lock-in 금지): refetchOnWindowFocus: true 0 hit (영구 캐시)
import { useQuery } from '@tanstack/react-query';
import { fetchAgentJarPath } from '../api/setup';

export interface AgentJarPathResult {
  /** server 자동 추출 절대경로. 추출 실패 또는 fetch 에러 시 null. */
  path: string | null;
  isLoading: boolean;
  isError: boolean;
}

export function useAgentJarPath(): AgentJarPathResult {
  const query = useQuery({
    queryKey: ['agent', 'jar-path'],     // [R10] SH-13 정합 — 정적
    queryFn: ({ signal }) => fetchAgentJarPath(signal),
    staleTime: Infinity,                 // [R10] AC-05-8 — server startup 1회 추출
    retry: 1,                            // [R10] 일시 네트워크 실패만 재시도
    refetchOnWindowFocus: false,         // [R10] 영구 캐시이므로 focus refetch 0
  });

  return {
    // path 의미 — query.data 가 { path: string | null } 형태. isError 시 fallback (null).
    path: query.data?.path ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
  };
}
