// 데이터 관리 섹션 — 운영자가 버튼으로 디스크 용량을 즉시 확보하는 수동 정리 UI.
//
// 배경: NAS 운영 앱이 52GB 를 쌓아 디스크가 위험했음 → 운영자가 설정 페이지에서 직접
//   ① 보관 기간 초과분 정리 ② 전체 비우기 두 동작으로 용량을 확보하고 싶어 함.
//
// 패턴 출처 (재발명 0):
//   - 카드 섹션 골격 + useMutation + useToast + stone 팔레트 → RetentionSection.tsx 동형.
//   - 파괴적 확인 모달 → Modal.tsx (AddRuleModal.tsx 사용 전례 — a11y 5종 + initialFocusRef=취소).
//   - 바이트 포맷 → lib/format.ts formatBytes (freedBytes 를 MB/GB 로 표시).
//   - BE 본문 노출 0 원칙 → 에러 시 고정 문구 토스트 (RetentionSection E-01 / AddRuleModal 동일).
//
// 확인 단계:
//   ① 지난 데이터 정리 — 인라인 2단계 확인 (버튼 클릭 → [확인]/[취소] 노출).
//   ② 전체 삭제 — destructive 강조(빨강) + 강한 확인: Modal 로 "정말 모든 로그를 삭제할까요?
//      되돌릴 수 없어요" 노출 후에만 실행 (의도치 않은 Enter 방지 위해 첫 focus = 취소).
import { useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Modal } from '../Modal';
import { runRetentionCleanup, purgeAllData, optimizeDatabase } from '../../api/maintenance';
import type { MaintenanceResult } from '../../types/api';
import { formatBytes } from '../../lib/format';
import { useToast } from '../useToast';

export function DataManagementSection(): ReactNode {
  const queryClient = useQueryClient();
  const toast = useToast();

  // ① cleanup 인라인 확인 단계 노출 여부 (가벼운 확인 — 같은 자리에서 [확인]/[취소]).
  const [confirmingCleanup, setConfirmingCleanup] = useState(false);
  // ② purge 모달 열림 여부 (강한 확인 — 파괴적이라 별도 모달).
  const [purgeModalOpen, setPurgeModalOpen] = useState(false);
  // [Phase K] (US-07) ③ optimize 인라인 확인 단계 노출 여부 (삭제 없음 — cleanup 동형 가벼운 확인).
  const [confirmingOptimize, setConfirmingOptimize] = useState(false);
  // 모달 첫 focus = 취소 버튼 (SH-09 — 의도치 않은 Enter 로 전체 삭제 방지).
  const cancelPurgeRef = useRef<HTMLButtonElement | null>(null);

  // 성공 후 무효화: ['settings'] (마지막 cleanup 시각/보관 설정), ['traces'] (목록), ['services']
  // (24시간 trace 수/health). prefix 매칭이라 ['traces', {...}] / ['services', 'detailed'] 모두 포함.
  const invalidateAfterMaintenance = (): void => {
    void queryClient.invalidateQueries({ queryKey: ['settings'] });
    void queryClient.invalidateQueries({ queryKey: ['traces'] });
    void queryClient.invalidateQueries({ queryKey: ['services'] });
  };

  const cleanup = useMutation({
    mutationFn: () => runRetentionCleanup(),
    onSuccess: (data: MaintenanceResult) => {
      setConfirmingCleanup(false);
      invalidateAfterMaintenance();
      toast.success(
        `지난 데이터를 정리했어요. (trace ${data.deletedTraces}건 삭제, 약 ${formatBytes(data.freedBytes)} 확보)`,
      );
    },
    onError: () => {
      // BE 본문 직접 노출 금지 — 고정 문구 (RetentionSection E-01 동일 원칙).
      setConfirmingCleanup(false);
      toast.error('정리에 실패했어요. 잠시 후 다시 시도해 주세요.');
    },
  });

  const purge = useMutation({
    mutationFn: () => purgeAllData(),
    onSuccess: (data: MaintenanceResult) => {
      setPurgeModalOpen(false);
      invalidateAfterMaintenance();
      toast.success(`모든 로그를 삭제했어요. (약 ${formatBytes(data.freedBytes)} 확보)`);
    },
    onError: () => {
      setPurgeModalOpen(false);
      toast.error('삭제에 실패했어요. 잠시 후 다시 시도해 주세요.');
    },
  });

  // [Phase K] (US-07, AC-07-1/AC-07-3/AC-07-4/AC-07-5) — optimize: 삭제 없이 전체 VACUUM 으로 조각 회수.
  //   busy 분기 토스트 (설계 §4.5 / planner §7.3 문구):
  //     - busy=true  + freedBytes==0 → 디스크 부족 거부 (T-C08, AC-07-4 — 52GB 사고 직결).
  //     - busy=true  + freedBytes>0  → 전체락 부분 회수 (T-C07, AC-07-3).
  //     - busy=false 또는 부재       → 정상 회수 (T-C06, AC-07-1).
  //   디스크 부족 vs 적재 busy 구분: 둘 다 server 가 busy=true 반환 → freedBytes 로 구분 (설계 §4.5).
  const optimize = useMutation({
    mutationFn: () => optimizeDatabase(),
    onSuccess: (data: MaintenanceResult) => {
      setConfirmingOptimize(false);
      invalidateAfterMaintenance();
      if (data.busy === true) {
        if (data.freedBytes === 0) {
          // T-C08 — 디스크 부족 거부 (실행 전 거부, freedBytes 0).
          toast.error(
            '디스크 여유 공간이 부족해 최적화를 건너뛰었어요. DB 크기 이상의 여유가 필요해요.',
          );
        } else {
          // T-C07 — 전체락 부분 회수.
          toast.error('적재 중이라 일부만 회수됐어요. 저사용 시간대에 다시 시도해 주세요.');
        }
        return;
      }
      // T-C06 — 정상 회수.
      toast.success(`파일 조각을 정리했어요. (약 ${formatBytes(data.freedBytes)} 확보)`);
    },
    onError: () => {
      // BE 본문 직접 노출 금지 — 고정 문구 (RetentionSection E-01 동일 원칙). T-C09.
      setConfirmingOptimize(false);
      toast.error('최적화에 실패했어요. 잠시 후 다시 시도해 주세요.');
    },
  });

  // [Phase K] (US-07, C-C01) — optimize 버튼 disabled = 세 동작 중 하나라도 실행 중 (전체락 충돌 회피, planner §8.1).
  const optimizeButtonDisabled = optimize.isPending || cleanup.isPending || purge.isPending;
  // [Phase K] (US-07, planner §8.2) — 역방향 동기화: optimize 실행 중이면 cleanup/purge 도 잠금 (전체락 충돌 회피).
  const cleanupButtonDisabled = cleanup.isPending || optimize.isPending;
  const purgeButtonDisabled = purge.isPending || optimize.isPending;

  // pending 중 닫기/취소 차단 (AddRuleModal handleClose 전례).
  const handleClosePurgeModal = (): void => {
    if (purge.isPending) return;
    setPurgeModalOpen(false);
  };

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      <h2 className="text-base font-medium text-stone-900">데이터 관리</h2>

      <div className="mt-4 space-y-4">
        <p className="text-xs text-stone-500">
          디스크 용량이 부족할 때 여기서 직접 로그를 정리해 용량을 확보할 수 있어요. 삭제한 데이터는
          되돌릴 수 없어요.
        </p>

        {/* ① 지난 데이터 정리 — cleanup 엔드포인트. 인라인 2단계 확인. */}
        <div className="flex flex-col gap-2 border-t border-stone-200 pt-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <p className="text-sm font-medium text-stone-900">지난 데이터 정리</p>
              <p className="mt-1 text-xs text-stone-500">
                보관 기간이 지난 trace 를 지금 즉시 삭제해요.
              </p>
            </div>
            {confirmingCleanup ? (
              <div className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  onClick={() => cleanup.mutate()}
                  // [Phase K] (US-07, planner §8.2): || optimize.isPending 역동기화 (전체락 충돌 회피).
                  disabled={cleanupButtonDisabled}
                  className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
                >
                  {cleanup.isPending ? '정리 중…' : '확인'}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmingCleanup(false)}
                  disabled={cleanupButtonDisabled}
                  className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                >
                  취소
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmingCleanup(true)}
                // [Phase K] (US-07, planner §8.2): || optimize.isPending 역동기화 (전체락 충돌 회피).
                disabled={cleanupButtonDisabled}
                className="shrink-0 rounded-md border border-stone-200 bg-white px-4 py-2 text-sm font-medium text-stone-900 hover:bg-stone-50 disabled:opacity-50"
              >
                지난 데이터 정리
              </button>
            )}
          </div>
          {confirmingCleanup && (
            <p className="text-xs text-stone-500">
              보관 기간이 지난 trace 를 지금 삭제할까요? 되돌릴 수 없어요.
            </p>
          )}
        </div>

        {/* ② 전체 삭제 — purge 엔드포인트. destructive 강조 + 모달 강한 확인. */}
        <div className="flex items-start justify-between gap-4 border-t border-stone-200 pt-4">
          <div>
            <p className="text-sm font-medium text-stone-900">전체 삭제</p>
            <p className="mt-1 text-xs text-stone-500">
              모든 로그(trace · span · payload)를 삭제해요. 되돌릴 수 없어요.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setPurgeModalOpen(true)}
            // [Phase K] (US-07, planner §8.2): || optimize.isPending 역동기화 (전체락 충돌 회피).
            disabled={purgeButtonDisabled}
            className="shrink-0 rounded-md bg-[var(--color-status-error)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            전체 삭제
          </button>
        </div>

        {/* [Phase K] (US-07, AC-07-1/AC-07-6) ③ 디스크 조각 정리(최적화) — optimize 엔드포인트.
            cleanup 동형 인라인 2단계 확인. cleanup/purge 와 의미 차별: "삭제 없음" 명시 (T-C02 — purge 혼동 차단). */}
        <div className="flex flex-col gap-2 border-t border-stone-200 pt-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              {/* T-C01 */}
              <p className="text-sm font-medium text-stone-900">디스크 조각 정리(최적화)</p>
              {/* T-C02 — "데이터를 삭제하지 않고" 명시로 purge 혼동 차단 (AC-07-6 의미 차별) */}
              <p className="mt-1 text-xs text-stone-500">
                데이터를 삭제하지 않고 파일 조각만 정리해 디스크 크기를 줄여요.
              </p>
            </div>
            {confirmingOptimize ? (
              <div className="flex shrink-0 items-center gap-2">
                <button
                  type="button"
                  onClick={() => optimize.mutate()}
                  // C-C01 — optimize·cleanup·purge 상호 배타 (전체락 충돌 회피, planner §8.1).
                  disabled={optimizeButtonDisabled}
                  className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
                >
                  {/* T-C04 */}
                  {optimize.isPending ? '최적화 중…' : '확인'}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmingOptimize(false)}
                  disabled={optimizeButtonDisabled}
                  className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                >
                  취소
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmingOptimize(true)}
                // C-C01 — optimize·cleanup·purge 상호 배타 (planner §8.1).
                disabled={optimizeButtonDisabled}
                className="shrink-0 rounded-md border border-stone-200 bg-white px-4 py-2 text-sm font-medium text-stone-900 hover:bg-stone-50 disabled:opacity-50"
              >
                {/* T-C03 */}
                최적화
              </button>
            )}
          </div>
          {confirmingOptimize && (
            // T-C05 — optimize 인라인 확인 안내
            <p className="text-xs text-stone-500">
              데이터는 그대로 두고 파일 조각만 정리할까요? 라이브 적재 중이면 일부만 회수될 수 있어요.
            </p>
          )}
        </div>
      </div>

      {/* 전체 삭제 강한 확인 모달 — 첫 focus = 취소 (의도치 않은 Enter 방지). */}
      <Modal
        open={purgeModalOpen}
        onClose={handleClosePurgeModal}
        title="전체 삭제"
        initialFocusRef={cancelPurgeRef}
      >
        <p>정말 모든 로그를 삭제할까요? 되돌릴 수 없어요.</p>
        {/* [Phase R13] FR-B2 — 대량 purge 는 동기 응답이 수 분 걸릴 수 있어 사용자 안내(텍스트, 컨트롤 0). */}
        <p className="mt-2 text-xs text-stone-500">
          대량 삭제는 수 분 걸릴 수 있어요. 완료될 때까지 창을 닫지 말고 기다려 주세요.
        </p>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            ref={cancelPurgeRef}
            onClick={handleClosePurgeModal}
            disabled={purge.isPending}
            className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={() => purge.mutate()}
            disabled={purge.isPending}
            className="rounded-md bg-[var(--color-status-error)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {purge.isPending ? '삭제 중…' : '확인'}
          </button>
        </div>
      </Modal>
    </section>
  );
}
