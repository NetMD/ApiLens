// 빈 상태 컴포넌트 — 2종 (FR-07).
// - "no-services": 등록된 서비스가 0개 (agent 미설치 / 데이터 미수집)
// - "no-traces": 선택 시간 범위에 trace 0건
//
// 라벨은 영어, 도움말은 한국어 (NFR-07 — 카피 정책 혼합).
import type { ReactNode } from 'react';

export type EmptyKind = 'no-services' | 'no-traces';

interface Props {
  kind: EmptyKind;
}

const COPY: Record<EmptyKind, { title: string; help: ReactNode }> = {
  'no-services': {
    title: 'No services yet',
    help: (
      <>
        아직 수집된 서비스가 없습니다. agent를 부착한 앱을 한 번 호출하면
        여기에 표시됩니다.
      </>
    ),
  },
  'no-traces': {
    title: 'No traces in this range',
    help: (
      <>
        선택한 시간 범위에 trace가 없습니다. 범위를 늘리거나 다른 서비스를 선택해 보세요.
      </>
    ),
  },
};

export function EmptyState({ kind }: Props): ReactNode {
  const { title, help } = COPY[kind];
  return (
    <div
      role="status"
      className="flex h-full min-h-40 flex-col items-center justify-center gap-2 rounded-lg border border-dashed border-stone-200 bg-stone-50 p-8 text-center"
    >
      <p className="text-base font-medium text-stone-900">{title}</p>
      <p className="max-w-md text-sm text-stone-500">{help}</p>
    </div>
  );
}
