// Phase R12 (FR-B2, AC-B2-5 FE 짝) — custom 룰 추가 모달 (NP-2 / P-R12-2, UX §3.4.3).
//
// 기존 Modal.tsx 재사용 (a11y 5종 충족 상태 그대로 — dialog/aria-modal/labelledby/focus trap/단순
// 오버레이). 첫 포커스 = 이름 input (입력 폼 표준 — SH-09 취소 우선은 파괴적 confirm 모달용,
// 의도치 않은 Enter 제출은 canAddRule 비활성으로 차단됨, UX §3.4.3).
//
// 검증: FE 사전 검증 best-effort (new RegExp try-catch) + 서버 400 (E-04) 이 최종 거부자 (BL-07).
// 인라인 에러 = T-20 2종 — FE 사전 검증과 서버 400 모두 동일 위치·동일 문구 (서버 본문 직접 노출 금지).
// is_default=0 은 서버 강제 (PLAN §5-2) — 폼에 default 여부 노출 0.
import { useEffect, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Modal } from '../Modal';
import { addMaskingRule } from '../../api/maskingRules';
import { ApiError } from '../../api/client';
import type { MaskingRuleType, MaskStrategy } from '../../types/api';
import { useToast } from '../useToast';

interface Props {
  open: boolean;
  onClose: () => void;
}

const RULE_TYPES: readonly MaskingRuleType[] = ['field_name', 'regex'] as const;
const MASK_STRATEGIES: readonly MaskStrategy[] = ['full', 'partial', 'hash', 'length_only'] as const;

/** T-20 인라인 에러 2종 — 필수 누락 / regex 오류. */
type InlineError = 'required' | 'regex' | null;

export function AddRuleModal({ open, onClose }: Props): ReactNode {
  const queryClient = useQueryClient();
  const toast = useToast();
  const nameInputRef = useRef<HTMLInputElement | null>(null);

  // 폼 입력 — 전부 useState (URL 키 0건, UX §4). select 기본 선택: field_name / full (UX §3.4.3).
  const [name, setName] = useState('');
  const [ruleType, setRuleType] = useState<MaskingRuleType>('field_name');
  const [pattern, setPattern] = useState('');
  const [maskStrategy, setMaskStrategy] = useState<MaskStrategy>('full');
  const [inlineError, setInlineError] = useState<InlineError>(null);

  // 모달 닫힘 시 폼 초기화 — 재오픈 시 빈 폼 (stale 입력 잔류 방지).
  useEffect(() => {
    if (!open) {
      setName('');
      setRuleType('field_name');
      setPattern('');
      setMaskStrategy('full');
      setInlineError(null);
    }
  }, [open]);

  const addRule = useMutation({
    mutationFn: () =>
      addMaskingRule({ name: name.trim(), ruleType, pattern: pattern.trim(), maskStrategy }),
    onSuccess: async () => {
      // 성공 피드백 = 모달 닫힘 + 목록 새 행 + 프리뷰 자동 갱신 — 토스트 없음 (UX §5.5 기획 의도 존중).
      await queryClient.invalidateQueries({ queryKey: ['masking-rules'] });
      onClose();
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.status === 400) {
        // E-04 서버 400 → T-20 동일 위치·동일 문구. FE 사전 검증을 통과한 400 은 regex 축이
        // 유일 (필수값은 canAddRule 로, enum 은 select 로 FE 차단됨 — 서버 메시지 직접 노출 금지).
        setInlineError('regex');
      } else {
        // 네트워크/5xx — UX §7.2 발의 #1 (ActiveServices 삭제 실패 토스트 형식 준용)
        toast.error('변경 실패 — 잠시 후 다시 시도해 주세요');
      }
    },
  });

  // planner §8.1.1 파생 상태 — 조건식 그대로 (C-04 코드 앵커).
  const canAddRule =
    name.trim() !== '' &&
    pattern.trim() !== '' &&
    (ruleType === 'field_name' || ruleType === 'regex') &&
    ['full', 'partial', 'hash', 'length_only'].includes(maskStrategy) &&
    !addRule.isPending;
  // regex 타입의 패턴 컴파일 검증은 FE best-effort — 서버 400 이 최종 (BL-07/E-04)

  const handleSubmit = (e: FormEvent): void => {
    e.preventDefault();
    if (!canAddRule) {
      // 필수 누락 — T-20 (제출 버튼은 C-04 로 이미 비활성이지만 Enter 경로 방어)
      if (name.trim() === '' || pattern.trim() === '') {
        setInlineError('required');
      }
      return;
    }
    // FE 사전 검증 (best-effort): regex 타입 → 컴파일 시도. 서버 400 이 최종 거부자.
    if (ruleType === 'regex') {
      try {
        new RegExp(pattern);
      } catch {
        setInlineError('regex');
        return;
      }
    }
    setInlineError(null);
    addRule.mutate();
  };

  // pending 중 닫기 차단 (ActiveServices 삭제 모달 전례 — UX §3.4.3).
  const handleClose = (): void => {
    if (addRule.isPending) return;
    onClose();
  };

  const inputClass =
    'w-full rounded-md border border-stone-200 px-3 py-1.5 text-sm text-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900';

  return (
    <Modal open={open} onClose={handleClose} title="룰 추가" initialFocusRef={nameInputRef}>
      <form onSubmit={handleSubmit} className="space-y-3">
        <div>
          {/* T-19 비고 확정 필드 라벨 4종: 이름 / 타입 / 패턴 / 마스킹 방식 */}
          <label htmlFor="add-rule-name" className="block text-sm font-medium text-stone-900">
            이름
          </label>
          <input
            id="add-rule-name"
            ref={nameInputRef}
            type="text"
            value={name}
            onChange={(e) => setName(e.target.value)}
            maxLength={100}
            className={`mt-1 ${inputClass}`}
          />
        </div>
        <div>
          <label htmlFor="add-rule-type" className="block text-sm font-medium text-stone-900">
            타입
          </label>
          {/* enum 값 그대로 표기 (데이터 값 표기 톤 — 한글 의역 금지, UX §3.4.2 동일 원칙) */}
          <select
            id="add-rule-type"
            value={ruleType}
            onChange={(e) => setRuleType(e.target.value as MaskingRuleType)}
            className={`mt-1 ${inputClass}`}
          >
            {RULE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="add-rule-pattern" className="block text-sm font-medium text-stone-900">
            패턴
          </label>
          <input
            id="add-rule-pattern"
            type="text"
            value={pattern}
            onChange={(e) => setPattern(e.target.value)}
            maxLength={1000}
            aria-describedby={inlineError !== null ? 'add-rule-error' : undefined}
            className={`mt-1 font-mono ${inputClass}`}
          />
        </div>
        <div>
          <label htmlFor="add-rule-strategy" className="block text-sm font-medium text-stone-900">
            마스킹 방식
          </label>
          <select
            id="add-rule-strategy"
            value={maskStrategy}
            onChange={(e) => setMaskStrategy(e.target.value as MaskStrategy)}
            className={`mt-1 ${inputClass}`}
          >
            {MASK_STRATEGIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>

        {inlineError !== null && (
          // T-20 2종 — 필드 영역 하단 공통 위치 (FE 사전 검증·서버 400 동일 위치·동일 문구)
          <p id="add-rule-error" className="text-xs text-[var(--color-status-error)]">
            {inlineError === 'required'
              ? '이름과 패턴을 입력해 주세요.'
              : '정규식 패턴이 올바르지 않아요.'}
          </p>
        )}

        <div className="flex justify-end gap-2 pt-2">
          {/* 취소 — ActiveServices 삭제 모달 전례 문구 준용 */}
          <button
            type="button"
            onClick={handleClose}
            disabled={addRule.isPending}
            className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
          >
            취소
          </button>
          {/* T-19 제출 — C-04: disabled = !canAddRule */}
          <button
            type="submit"
            disabled={!canAddRule}
            className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
          >
            추가
          </button>
        </div>
      </form>
    </Modal>
  );
}
