// [Phase R15] AC-B3-1 — 전역 상단 고정 배너. 수신 일시정지 중(paused=true)일 때만 노출.
//
// 사용자 명시 비협상 결정(D03 in-memory 상태, D06 정리 미강제 안내). CLAUDE.md 'UI 디자인 철학'
// (운영자는 "흐름과 끊긴 지점"이 궁금 — 데이터가 저장 안 되는 상태를 즉시 인지시킴).
// neutral(amber-tint) 스타일 — red 부적절(되돌릴 수 있는 일시정지이지 파괴적 동작 아님).
import type { ReactNode } from 'react';
import { useMaintenanceStatus } from '../hooks/useMaintenanceStatus';

/**
 * [Phase R15] AC-B3-1 — paused=true 면 화면 최상단 1행 배너(흐름 배치, NavHeader 위에 쌓임), paused=false 면 미렌더.
 *
 * App.tsx 의 <FirstRunGuard> 내부·<Routes> 직전에 삽입돼 모든 화면 상단에 노출된다(GT-10).
 */
export function MaintenanceModeBanner(): ReactNode {
  const { paused, elapsedMinutes } = useMaintenanceStatus();
  if (!paused) return null; // 수신 중이면 미렌더(배너 미노출 — 거짓 일시정지 0)
  return (
    <div
      role="status"
      className="border-b border-amber-200 bg-amber-50 px-6 py-2 text-center text-sm text-amber-900"
    >
      {/* T-01 — N분 경과. neutral(amber) 스타일, red 부적절(되돌릴 수 있음). 사용자 자연어 + 존댓말. */}
      수신 일시정지 중 ({elapsedMinutes}분 경과) — 이 동안 들어온 데이터는 저장되지 않습니다.
    </div>
  );
}
