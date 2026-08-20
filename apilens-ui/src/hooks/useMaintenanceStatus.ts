// [Phase R15] AC-A3-1/AC-B1-1/AC-B3-1/AC-B5-1 — 수신 일시정지 상태 폴링 hook.
//
// 단일 queryKey ['maintenance','status'] — 배너(MaintenanceModeBanner)·배지(ActiveServices)·
// Dashboard 폴링 조건이 모두 이 hook 을 공유한다 (React Query dedupe — 1회 폴링으로 3곳 동기화, BL-07).
// 사용자 명시 비협상 결정(D03 in-memory 상태, D08 cap backstop). CLAUDE.md '아키텍처 핵심 원칙' (수신 일시정지 단일 기능).
import { useQuery } from '@tanstack/react-query';
import { getMaintenanceStatus } from '../api/maintenance';

// [Phase R15] AC-B1-1 — 폴링 주기. 10~30초 중앙값(폴링 부하 ↔ 인지 지연 균형, 설계 §0.2 B/architect 확정).
// 매직넘버 금지 — 명명 상수(EXT-001). 사용자 명시 비협상 결정(B UI 폴링 동기화).
const MAINTENANCE_REFETCH_MS = 15_000; // 15초

export interface MaintenanceStatusView {
  paused: boolean;
  pausedAt: number | null;
  /** (now - pausedAt) / 60000 내림. paused=false 면 0. */
  elapsedMinutes: number;
  /** [R21/AC-03-4] 적재 중 SQLITE_BUSY 를 만난 누적 횟수 (BE in-memory — 재시작 시 0 복귀 정상). */
  sqliteBusyEncountered: number;
  /** [R21/AC-03-4] 경합으로 유실된 누적 청크 수 (청크 ≈ 500 span — 횟수와 단위가 다르다, T-15). */
  sqliteBusyDropped: number;
  /**
   * [Phase T / R23] AC-06-2 — 요약을 저장하지 못한 누적 흐름 수(건).
   * 위 두 필드와 단위가 또 다르다 — 횟수 / 청크 수 / 흐름 수. BE in-memory(재시작 시 0 복귀 정상).
   */
  traceSummaryDeferred: number;
  /**
   * [Phase T / R23] AC-07-1 — DB 파일 크기(바이트). BE 가 PRAGMA page_count × page_size 로 조립한다.
   * ⚠️ 위 카운터 3종과 성질이 다르다 — DB 에서 읽는 값이라 서버를 재시작해도 0 이 되지 않는다.
   * 그래서 화면에서도 「적재 상태」와 다른 구획에 놓는다(설계 §2.5-A — 사용자 명시 비협상 결정).
   */
  dbSizeBytes: number;
  /**
   * [Phase T / R23] AC-07-1 — 회수 가능한 빈 공간(바이트). BE 가 PRAGMA freelist_count × page_size 로 조립한다.
   * ⚠️ 0 은 결함이 아니다 — 전체 삭제 + 최적화 직후에는 정상값이다(2026-08-13 운영 실측).
   */
  freePageBytes: number;
}

/**
 * [Phase R15] AC-A3-1 — GET /v1/maintenance/status 를 15초 주기 폴링하고 elapsedMinutes 를 파생한다.
 *
 * ⚠️ 폴링 실패/초기 로딩 시 paused=false fallback (거짓 일시정지 차단, 설계 §2.7/§8 #5):
 *    status GET 이 실패(네트워크/401)하거나 초기 로딩이면 query.data===undefined → paused=false →
 *    배너·배지 미표시. 실제 수신 중인데 배너가 뜨는 거짓 일시정지를 회피한다(데이터 유실 오인 방지).
 *    일시정지 중 폴링이 끊겨 배너가 사라져도 BE max-pause cap(D08, 30분 자가 재개)이 backstop.
 */
export function useMaintenanceStatus(): MaintenanceStatusView {
  const query = useQuery({
    queryKey: ['maintenance', 'status'],
    queryFn: ({ signal }) => getMaintenanceStatus(signal),
    refetchInterval: MAINTENANCE_REFETCH_MS,
    refetchOnWindowFocus: false,
  });

  // 폴링 실패/초기 로딩 시 query.data === undefined → paused=false (거짓 일시정지 차단, 배너 미표시).
  const paused = query.data?.paused ?? false;
  const pausedAt = query.data?.pausedAt ?? null;
  // elapsedMinutes 는 폴링 주기(15초)마다 리렌더 시 Date.now() 로 재계산 — 분 단위라 별도 1초 타이머 불요(과설계 회피).
  const elapsedMinutes =
    paused && pausedAt !== null ? Math.floor((Date.now() - pausedAt) / 60_000) : 0;
  // [R21/AC-03-4, S-115 적용] `?? 0` — 기존 `?? false` 패턴 동형. 로딩 중 undefined 와
  // 구형 factory(2필드 응답)를 한 분기로 흡수 (카운터 0 = 정상값이라 폴백도 정상 표시).
  const sqliteBusyEncountered = query.data?.sqliteBusyEncountered ?? 0;
  const sqliteBusyDropped = query.data?.sqliteBusyDropped ?? 0;
  // [Phase T / R23] AC-06-2/AC-07-1 — 새 3필드도 같은 `?? 0` 형태로 흡수한다 (형태 동형 — 새 판별식 0).
  // ⚠️ 이 폴백이 있어서 픽스처를 하나도 안 고쳐도 화면 테스트가 전부 통과한다 —
  //    픽스처가 낡았는지는 검색 게이트 2축으로만 판정한다(설계 §6.3 / §7.3).
  //    폴백 자체를 잠그는 테스트가 src/test/useMaintenanceStatus.test.tsx 의
  //    exposesZeroCountersWhenFieldsAbsent 이고, 그 픽스처는 손대면 안 된다(불변식 I-12).
  const traceSummaryDeferred = query.data?.traceSummaryDeferred ?? 0;
  const dbSizeBytes = query.data?.dbSizeBytes ?? 0;
  const freePageBytes = query.data?.freePageBytes ?? 0;

  return {
    paused,
    pausedAt,
    elapsedMinutes,
    sqliteBusyEncountered,
    sqliteBusyDropped,
    traceSummaryDeferred,
    dbSizeBytes,
    freePageBytes,
  };
}
