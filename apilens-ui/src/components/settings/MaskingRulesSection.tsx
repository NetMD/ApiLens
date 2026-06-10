// Phase R12 (FR-B2/B3, AC-B4-2 섹션 ②) — Masking rules 섹션 (UX §3.4).
//
// [R12] D-02 비협상 — "설정 페이지에 마스킹 룰 관리 UI 포함 — 목록/토글/추가·삭제 + 라이브 프리뷰.
// '결재용 신뢰 도구'". 2컬럼 (lg 이상): 좌 룰 목록 / 우 sticky 프리뷰 — 토글과 프리뷰가 한 시야
// (결재 시연 요건, UX §3.4.1).
//
// 토글 ↔ 프리뷰 낙관적 모델 (UX §5.1 그대로 — 설계 §3.2.4 채택):
//   [토글 클릭]
//     ① 로컬 룰 세트 상태 즉시 반전 (낙관) → 스위치 비주얼 즉답
//     ② 프리뷰 디바운스 재요청 — 요청 본문에 "화면의 현재 룰 세트 상태" 명시 동봉
//        (서버 DB persisted 상태 의존 0 → mutation 완료와의 race 원천 차단)
//     ③ toggleRule mutation 발사 (해당 행 C-03 disabled)
//        ├ 성공 → 서버 확인 상태 = 로컬 → dirty 해제
//        └ 실패 → 로컬 상태 롤백 + 에러 토스트 (발의 #1) + 프리뷰 재요청 (롤백 상태)
//              └ 404 (E-03) → 추가로 룰 목록 invalidate (행 자체가 사라진 경우 동기화)
//
// dirty 정의 (T-23 노출 조건): 화면 룰 세트 상태 ≠ 마지막 서버 확인 상태 (UX §5.1).
import { useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { deleteMaskingRule, listMaskingRules, toggleMaskingRule } from '../../api/maskingRules';
import { ApiError } from '../../api/client';
import type { MaskingRule, MaskingRulesResponse, PreviewRuleState } from '../../types/api';
import { MaskingRuleRow } from './MaskingRuleRow';
import { MaskingPreview } from './MaskingPreview';
import { AddRuleModal } from './AddRuleModal';
import { LoadingSkeleton } from '../LoadingSkeleton';
import { useToast } from '../useToast';

export function MaskingRulesSection(): ReactNode {
  const queryClient = useQueryClient();
  const toast = useToast();

  // 섹션 독립 쿼리 — Retention 과 분리 (한쪽 실패가 다른 쪽 차단 0, UX §5.3). staleTime 짧게.
  const rulesQuery = useQuery({
    queryKey: ['masking-rules'],
    queryFn: ({ signal }) => listMaskingRules(signal),
    staleTime: 0,
    retry: 1,
  });

  // 낙관적 토글 오버라이드 — ruleId → 화면 enabled. 항목 부재 = 서버 확인 상태 그대로.
  const [overrides, setOverrides] = useState<Record<number, boolean>>({});
  // C-03 — 해당 행 mutation pending 추적 (pendingRuleId).
  const [pendingRuleId, setPendingRuleId] = useState<number | null>(null);
  const [addModalOpen, setAddModalOpen] = useState(false);

  const serverRules = rulesQuery.data?.rules ?? [];
  // 화면 상태 = 서버 확인 상태 + 낙관 오버라이드 (프리뷰 요청 본문의 단일 소스 — AC-B3-1).
  const screenRules: MaskingRule[] = serverRules.map((r) => ({
    ...r,
    enabled: overrides[r.ruleId] ?? r.enabled,
  }));
  // dirty = 화면 상태 ≠ 서버 확인 상태 (T-23 노출 조건 — UX §5.1 정의 그대로).
  const dirty = serverRules.some((r) => (overrides[r.ruleId] ?? r.enabled) !== r.enabled);

  const ruleStates: PreviewRuleState[] = screenRules.map((r) => ({
    ruleId: r.ruleId,
    enabled: r.enabled,
  }));

  const removeOverride = (ruleId: number): void => {
    setOverrides((prev) => {
      const next = { ...prev };
      delete next[ruleId];
      return next;
    });
  };

  const toggleMutation = useMutation({
    mutationFn: ({ ruleId, enabled }: { ruleId: number; enabled: boolean }) =>
      toggleMaskingRule(ruleId, enabled),
    onSuccess: (updated: MaskingRule) => {
      // 성공 → 서버 확인 상태 = 로컬 (응답 룰로 캐시 갱신) → 오버라이드 제거 = dirty 해제.
      queryClient.setQueryData<MaskingRulesResponse>(['masking-rules'], (prev) =>
        prev
          ? { rules: prev.rules.map((r) => (r.ruleId === updated.ruleId ? updated : r)) }
          : prev,
      );
      removeOverride(updated.ruleId);
    },
    onError: (err: unknown, vars) => {
      // 실패 → 로컬 상태 롤백 (오버라이드 제거 = 서버 확인 상태로 복귀) — 프리뷰는 ruleStates
      // 변화로 자동 재요청 (롤백 상태 기준).
      removeOverride(vars.ruleId);
      // UX §7.2 발의 #1 — 토글 mutation 실패 토스트
      toast.error('변경 실패 — 잠시 후 다시 시도해 주세요');
      if (err instanceof ApiError && err.status === 404) {
        // E-03 — 행 자체가 사라진 경우 목록 동기화 (UX §5.4 정정표: 발의 #1 + invalidate)
        void queryClient.invalidateQueries({ queryKey: ['masking-rules'] });
      }
    },
    onSettled: () => {
      setPendingRuleId(null);
    },
  });

  const handleToggle = (rule: MaskingRule): void => {
    // rule.enabled 는 화면 상태 반영본 — 반전 목표값.
    const next = !rule.enabled;
    setOverrides((prev) => ({ ...prev, [rule.ruleId]: next })); // ① 낙관 반전 (즉답)
    setPendingRuleId(rule.ruleId);
    toggleMutation.mutate({ ruleId: rule.ruleId, enabled: next }); // ③ mutation 발사
    // ② 프리뷰 재요청은 ruleStates 변화 → MaskingPreview 디바운스 200ms 가 자동 수행.
  };

  const deleteMutation = useMutation({
    mutationFn: (ruleId: number) => deleteMaskingRule(ruleId),
    onSuccess: async () => {
      // 성공 피드백 = 행 제거 + 카운트 T-13 갱신 + 프리뷰 자동 갱신 — 토스트 없음 (UX §5.5).
      await queryClient.invalidateQueries({ queryKey: ['masking-rules'] });
    },
    onError: (err: unknown) => {
      if (err instanceof ApiError && err.status === 409) {
        // T-25 — E-02 default 삭제 거부 (UI 1차 방어는 비노출 — 2차 방어 도달 시 토스트)
        toast.error('기본 제공 룰은 삭제할 수 없어요.');
      } else {
        toast.error('변경 실패 — 잠시 후 다시 시도해 주세요'); // 발의 #1
        if (err instanceof ApiError && err.status === 404) {
          void queryClient.invalidateQueries({ queryKey: ['masking-rules'] });
        }
      }
    },
  });

  // T-13 — 활성 = enabled=1 (화면 상태 기준), 전체 = default+custom 합산 (제외 없음).
  const activeCount = screenRules.filter((r) => r.enabled).length;
  const totalCount = screenRules.length;
  const customCount = screenRules.filter((r) => !r.isDefault).length;

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      <div className="flex items-center justify-between">
        {/* T-12 */}
        <h2 className="text-base font-medium text-stone-900">Masking rules</h2>
        {/* T-13 — TraceList 헤더 카운트 전례 동형 */}
        {rulesQuery.data && (
          <span className="text-xs text-stone-500">
            활성 {activeCount} / 전체 {totalCount}
          </span>
        )}
      </div>

      {rulesQuery.isError ? (
        // E-10 — T-27 + 다시 시도 (Retention 과 독립 — 섹션 단위 에러, UX §5.3)
        <div className="mt-4 flex flex-col items-start gap-3">
          <p className="text-sm text-stone-500">설정을 불러오지 못했어요.</p>
          <button
            type="button"
            onClick={() => void rulesQuery.refetch()}
            className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
          >
            다시 시도
          </button>
        </div>
      ) : rulesQuery.isLoading ? (
        <div className="mt-4">
          <LoadingSkeleton variant="list" />
        </div>
      ) : (
        <>
          {/* 2컬럼 — lg 이상 grid, 미만 세로 스택 (목록 → 프리뷰). 카드 안 카드 중첩 금지 (UX §3.2) */}
          <div className="mt-4 grid grid-cols-1 gap-6 lg:grid-cols-2">
            {/* 좌: 룰 목록 */}
            <div>
              <ul className="divide-y divide-stone-200">
                {screenRules.map((rule) => (
                  <MaskingRuleRow
                    key={rule.ruleId}
                    rule={rule}
                    togglePending={toggleMutation.isPending && pendingRuleId === rule.ruleId}
                    deletePending={deleteMutation.isPending}
                    onToggle={handleToggle}
                    onDelete={(r) => deleteMutation.mutate(r.ruleId)}
                  />
                ))}
              </ul>
              {customCount === 0 && (
                // T-18 — custom 0건 빈 상태 행 (default 4종은 상존 — 목록 전체 빈 상태는 정상 경로에 없음)
                <div className="mt-3 rounded-md border border-dashed border-stone-200 bg-stone-50 p-4 text-center text-sm text-stone-500">
                  커스텀 룰이 아직 없어요. &lsquo;룰 추가&rsquo;로 만들 수 있어요.
                </div>
              )}
              {/* T-19 열기 버튼 — secondary */}
              <button
                type="button"
                onClick={() => setAddModalOpen(true)}
                className="mt-3 rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
              >
                룰 추가
              </button>
            </div>

            {/* 우: 라이브 프리뷰 — lg 에서 sticky (목록이 길어져도 토글↔프리뷰 동시 가시, UX §3.4.1) */}
            <div className="lg:sticky lg:top-6 lg:self-start">
              <MaskingPreview ruleStates={ruleStates} dirty={dirty} />
            </div>
          </div>

          {/* T-26 — BL-06 사용자 노출 명문 (섹션 하단 보조) */}
          <p className="mt-4 border-t border-stone-200 pt-3 text-xs text-stone-500">
            룰 변경은 이후 수집되는 trace 부터 적용돼요. 이미 저장된 payload 는 다시 마스킹하지
            않아요.
          </p>
        </>
      )}

      <AddRuleModal open={addModalOpen} onClose={() => setAddModalOpen(false)} />
    </section>
  );
}
