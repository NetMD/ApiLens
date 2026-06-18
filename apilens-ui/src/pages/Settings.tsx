// Phase R12 (FR-B4, AC-B4-2) — /settings 페이지 골격 (UX §3.2).
//
// [R12] D-02 비협상 — "설정 페이지에 마스킹 룰 관리 UI 포함 — 목록/토글/추가·삭제 + 라이브 프리뷰.
// '결재용 신뢰 도구'".
//
// 구조: 네비 전용 헤더 (NavHeader) + 단일 스크롤 2섹션 (탭 아님):
//   ① Retention 섹션 (T-04) ② Masking rules 섹션 (T-12 — 내부 lg 2컬럼: 좌 룰 목록 / 우 프리뷰).
// 두 섹션의 데이터 소스 독립 (GET /v1/settings ↔ GET /v1/masking-rules) — 로딩/에러도 섹션 단위
// 독립 (한쪽 실패가 다른 쪽을 차단하지 않음, UX §5.3).
//
// URL 키: 0건 — 페이지 내 모든 상태 useState (UX §4 확정 — G-19 조건부 미발동.
// 모달 열림/샘플 소스/입력 내용을 URL 에 박지 않음 — Setup SH-11 전례).
import type { ReactNode } from 'react';
import { NavHeader } from '../components/NavHeader';
import { RetentionSection } from '../components/settings/RetentionSection';
import { DataManagementSection } from '../components/settings/DataManagementSection';
import { MaskingRulesSection } from '../components/settings/MaskingRulesSection';

export function Settings(): ReactNode {
  return (
    <div className="flex h-full flex-col bg-stone-50">
      <NavHeader />
      <main className="flex-1 overflow-auto px-6 py-6">
        <div className="mx-auto max-w-5xl space-y-6">
          {/* T-03 — h1, ActiveServices.tsx h1 전례 동형 */}
          <h1 className="text-lg font-medium text-stone-900">Settings</h1>
          <RetentionSection />
          {/* 데이터 관리 — 디스크 용량 수동 확보 (보관 정책의 Retention 섹션 바로 아래 배치). */}
          <DataManagementSection />
          <MaskingRulesSection />
        </div>
      </main>
    </div>
  );
}
