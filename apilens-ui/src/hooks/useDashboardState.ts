// 대시보드 URL state 통합 훅 (BL-04, BL-05, BL-06).
// - service, live, range: 모두 URL 반영 (?service=&live=&range=). useSearchParams + replace: true.
//   → trace 상세에서 뒤로가기 시 history stack의 URL 그대로 복원되어 선택이 유지됨.
//   (이전 PM FR-05의 "range는 URL 비반영" 결정은 뒤로가기 UX 문제로 v0.1.1에서 뒤집힘)
//
// [R10] AC-02-2 / AC-02-3 (D-H10-02 경로 B 비협상) — services 1건 자동 default selection.
//   - ?service= 없고 services 정확 1건일 때만 setService 호출
//   - services.length >= 2 또는 0 시 기존 동작 유지 (수동 선택 강요)
//   - SH-13 정합 — queryKey 정적 ['services'] (Dashboard 의 servicesQuery 와 dedupe)
//   - SH-06 정합 — setService 가 setSearchParams 호출 → URL 보존
//
// 회귀 가드 grep:
//   정방향: services.length === 1 정확 1 hit
//   반대 (lock-in 금지): services.length >= 1 / services.length > 0 / services.length >= 2 0 hit
import { useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { listServices } from '../api/traces';
import type { RangePreset } from '../lib/time';

const VALID_RANGES: readonly RangePreset[] = ['1m', '5m', '10m', '1h', '24h', '7d'] as const;

function parseRange(raw: string | null): RangePreset {
  if (raw !== null && (VALID_RANGES as readonly string[]).includes(raw)) {
    return raw as RangePreset;
  }
  return '10m';
}

// [R12] D-03 비협상 — 필터는 status + operation 검색(q)만. duration 필터 추가 금지.
// status/q 는 search-persist (ROUTE_LOCAL_PARAMS 등재 금지 — 미등재가 곧 전 route 보존, BL-10/G-19).
// AC-C1-1 verbatim: "useDashboardState 에 ?status= 키 (값 OK/ERROR, 전체 = 키 부재 — §9).
// 기존 updateParams(replace:true) 패턴 편승 (G-18) — searchParams 직접 조작 금지, routeSearch 불변식 경유".
export type StatusFilter = 'OK' | 'ERROR';

/** E-12: 무효값 → 전체 (parseRange 동형 정규화 — 'FOO' 등은 null = 키 무시). */
export function parseStatus(raw: string | null): StatusFilter | null {
  return raw === 'OK' || raw === 'ERROR' ? raw : null;
}

export interface DashboardState {
  /** 선택된 service. URL의 ?service=. 미선택 시 null → traces query disabled. */
  service: string | null;
  /** Live 토글. URL의 ?live=true. 기본 false. */
  live: boolean;
  /** 시간 범위. URL의 ?range=. 기본 '10m'(이때는 URL에서 생략). */
  range: RangePreset;
  /** [R12] status 필터. URL의 ?status= (OK|ERROR). 전체 = 키 부재 = null (AC-C1-1). */
  status: StatusFilter | null;
  /** [R12] operation 검색어. URL의 ?q=. 빈 문자열 = 키 부재 (AC-C2-3). */
  q: string;
  setService: (next: string | null) => void;
  setLive: (next: boolean) => void;
  setRange: (next: RangePreset) => void;
  setStatus: (next: StatusFilter | null) => void;
  setQ: (next: string) => void;
}

export function useDashboardState(): DashboardState {
  const [searchParams, setSearchParams] = useSearchParams();

  const service = searchParams.get('service');
  const live = searchParams.get('live') === 'true';
  const range = parseRange(searchParams.get('range'));
  // [R12] AC-C1-1/AC-C2-3 — 무효 status 값은 전체로 정규화 (E-12), q 부재 = ''.
  const status = parseStatus(searchParams.get('status'));
  const q = searchParams.get('q') ?? '';

  const updateParams = useCallback(
    (mutate: (params: URLSearchParams) => void) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          mutate(next);
          return next;
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const setService = useCallback(
    (next: string | null) => {
      updateParams((p) => {
        if (next === null || next === '') {
          p.delete('service');
        } else {
          p.set('service', next);
        }
      });
    },
    [updateParams],
  );

  const setLive = useCallback(
    (next: boolean) => {
      updateParams((p) => {
        if (next) {
          p.set('live', 'true');
        } else {
          p.delete('live');
        }
      });
    },
    [updateParams],
  );

  const setRange = useCallback(
    (next: RangePreset) => {
      updateParams((p) => {
        // default('10m')는 URL에서 비워둠 — 공유 링크/북마크 깔끔하게.
        if (next === '10m') {
          p.delete('range');
        } else {
          p.set('range', next);
        }
      });
    },
    [updateParams],
  );

  // [R12] AC-C1-1 — setStatus(null) → 키 제거 (전체 = 키 부재) / 그 외 set. updateParams(replace:true) 편승.
  const setStatus = useCallback(
    (next: StatusFilter | null) => {
      updateParams((p) => {
        if (next === null) {
          p.delete('status');
        } else {
          p.set('status', next);
        }
      });
    },
    [updateParams],
  );

  // [R12] AC-C2-3 — trim 후 '' → 키 제거 / 그 외 set (설계 §3.2.5 그대로).
  const setQ = useCallback(
    (next: string) => {
      updateParams((p) => {
        if (next.trim() === '') {
          p.delete('q');
        } else {
          p.set('q', next);
        }
      });
    },
    [updateParams],
  );

  // [R10] AC-02-2 (D-H10-02 경로 B 비협상) — services 자동 등록 1건만 default selection.
  // SH-13 정합 — queryKey 정적 (시간 변수 박지 말 것). ['services'] dedupe 로 Dashboard servicesQuery 와 공유.
  const servicesQuery = useQuery({
    queryKey: ['services'],
    queryFn: ({ signal }) => listServices(signal),
    staleTime: 30_000,
    retry: 1,
  });

  useEffect(() => {
    // 자동 선택 조건 3개 모두 충족 시에만 setService 호출:
    //   1) URL 에 ?service= 없음 (사용자 명시 선택 보존)
    //   2) services 목록 fetch 완료 + 정확 1건
    //   3) services 1건이 valid name
    // 경로 C (services.length >= 2) 는 본 useEffect 진입 안 함 (잘못된 lock-in 차단).
    if (service !== null) return;
    if (!servicesQuery.data) return;
    const svcs = servicesQuery.data.services;
    // [R10] AC-02-2 — services 정확 1건일 때만 자동. >= 1 / > 0 lock-in 금지.
    if (svcs.length === 1 && svcs[0]?.name) {
      setService(svcs[0].name);
    }
  }, [service, servicesQuery.data, setService]);

  return { service, live, range, status, q, setService, setLive, setRange, setStatus, setQ };
}
