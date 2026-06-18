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
import { runRetentionCleanup, purgeAllData } from '../../api/maintenance';
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
                  disabled={cleanup.isPending}
                  className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
                >
                  {cleanup.isPending ? '정리 중…' : '확인'}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmingCleanup(false)}
                  disabled={cleanup.isPending}
                  className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                >
                  취소
                </button>
              </div>
            ) : (
              <button
                type="button"
                onClick={() => setConfirmingCleanup(true)}
                disabled={cleanup.isPending}
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
            disabled={purge.isPending}
            className="shrink-0 rounded-md bg-[var(--color-status-error)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            전체 삭제
          </button>
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
