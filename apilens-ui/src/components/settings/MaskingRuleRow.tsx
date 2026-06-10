// Phase R12 (FR-B2, AC-B4-2 섹션 ②) — 마스킹 룰 목록 행 (UX §3.4.2).
//
// [R12] AC-B4-3 verbatim: "default 룰 행에 삭제 버튼 비노출 (1차 방어 — UI) + API 4xx (2차 방어 —
// US-B2)" (비협상) — C-05: 렌더 조건 rule.isDefault === false (disabled 가 아닌 **렌더 자체 제거**.
// default 행에는 삭제 버튼 DOM 자체 부재).
//
// 토글: C-03 — default 룰도 토글 가능 (비활성만 가능 = 토글 허용, 삭제만 금지).
// 스위치 비주얼 = Toggle.tsx 동형 토큰 (행 레이아웃이 달라 스위치 부분만 추출 — UX §8.2 dev 재량).
import type { ReactNode } from 'react';
import type { MaskingRule } from '../../types/api';

interface Props {
  /** 화면 상태 반영본 — enabled 는 낙관 토글 오버라이드 적용 후 값. */
  rule: MaskingRule;
  /** C-03: 해당 행 mutation pending 만 disabled (다른 행은 영향 0). */
  togglePending: boolean;
  /** C-05: deleteRule.isPending. */
  deletePending: boolean;
  onToggle: (rule: MaskingRule) => void;
  onDelete: (rule: MaskingRule) => void;
}

export function MaskingRuleRow({
  rule,
  togglePending,
  deletePending,
  onToggle,
  onDelete,
}: Props): ReactNode {
  return (
    <li className="flex items-center gap-3 px-4 py-3">
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-stone-900">{rule.name}</span>
          {rule.isDefault ? (
            // T-14 — 텍스트 라벨이 1차 구분 (색 단독 구분 금지)
            <span className="rounded bg-stone-100 px-1.5 py-0.5 text-[10px] font-medium text-stone-500">
              기본 제공
            </span>
          ) : (
            // T-15
            <span className="rounded border border-stone-200 bg-white px-1.5 py-0.5 text-[10px] font-medium text-stone-500">
              커스텀
            </span>
          )}
        </div>
        {/* 메타 줄 — enum 값은 데이터 값 표기 톤 그대로 (한글 의역 금지, UX §3.4.2) */}
        <p className="mt-0.5 truncate font-mono text-xs text-stone-500" title={rule.pattern}>
          {rule.ruleType} · {rule.pattern} · {rule.maskStrategy}
        </p>
      </div>

      {/* C-05 1차 방어 — rule.isDefault === false 일 때만 렌더 (비노출. default 행 DOM 자체 부재) */}
      {rule.isDefault === false && (
        <button
          type="button"
          onClick={() => onDelete(rule)}
          disabled={deletePending}
          aria-label={`${rule.name} 삭제`}
          className="rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-[var(--color-status-error)] disabled:cursor-not-allowed disabled:opacity-50"
        >
          삭제
        </button>
      )}

      {/* 토글 스위치 — Toggle.tsx 비주얼 토큰 동형 (role="switch" + aria-checked). aria-label = T-16 */}
      <button
        type="button"
        role="switch"
        aria-checked={rule.enabled}
        aria-label={`${rule.name} 활성화`}
        disabled={togglePending}
        onClick={() => onToggle(rule)}
        className={
          rule.enabled
            ? 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full bg-stone-900 transition-colors focus:outline-none focus:ring-2 focus:ring-stone-900 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50'
            : 'relative inline-flex h-6 w-11 shrink-0 cursor-pointer rounded-full bg-stone-200 transition-colors focus:outline-none focus:ring-2 focus:ring-stone-900 focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50'
        }
      >
        <span
          aria-hidden
          className={
            rule.enabled
              ? 'inline-block h-5 w-5 translate-x-5 transform rounded-full bg-white shadow transition-transform'
              : 'inline-block h-5 w-5 translate-x-0.5 transform rounded-full bg-white shadow transition-transform'
          }
          style={{ marginTop: 2 }}
        />
      </button>
    </li>
  );
}
