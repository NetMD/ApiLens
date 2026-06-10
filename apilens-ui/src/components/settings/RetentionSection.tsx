// Phase R12 (FR-B1, AC-B4-2 섹션 ①) — Retention 섹션 (UX §3.3).
//
// [R12] D-05 비협상 — "retention 기본 30일 유지 + 설정 페이지에서 변경 가능 (DB 저장 값이 yml 보다
// 우선)". resolve 는 서버 책임 — FE 는 GET /v1/settings 의 resolve 된 값을 prefill 만 한다.
//
// disabled 논리식 = planner §8.1/§8.1.1 그대로 (재발명 0 — 설계 §8.3):
//   C-01: input disabled = settingsQuery.isLoading || saveSettings.isPending
//   C-02: 저장 disabled = !canSaveRetention
//   canSaveRetention = isValidRetention && retentionInput !== savedRetention && !saveSettings.isPending
//
// 문구 = PLAN §7 확정값: T-04~T-11 + T-27 (재발명 0).
import { useState } from 'react';
import type { ReactNode } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getSettings, saveSettings as saveSettingsApi } from '../../api/settings';
import type { SettingsResponse } from '../../types/api';
import { RETENTION_MAX, RETENTION_MIN } from '../../lib/constants';
import { useToast } from '../useToast';

export function RetentionSection(): ReactNode {
  const queryClient = useQueryClient();
  const toast = useToast();

  // staleTime 짧게 (0~2s — 설정 화면 재진입 시 신선값, 설계 §3.2.3). 섹션 독립 쿼리.
  const settingsQuery = useQuery({
    queryKey: ['settings'],
    queryFn: ({ signal }) => getSettings(signal),
    staleTime: 0,
    retry: 1,
  });

  // 서버 확인 저장값 — dirty 판정 기준 (응답 = resolve 된 유효값).
  const savedRetention = settingsQuery.data?.settings['retention.days'];
  const lastCleanupAt = settingsQuery.data?.lastCleanupAt;

  // 입력 raw 문자열 — null = 사용자 미입력 (prefill 표시). useState 단일 (URL 키 0건 — UX §4).
  const [rawInput, setRawInput] = useState<string | null>(null);
  // T-08 은 blur 이후에만 표시 (Setup.tsx *Blurred 전례 — 첫 입력 중 에러 억제).
  const [blurred, setBlurred] = useState(false);
  // 서버 400 도달 시에도 동일 위치·동일 문구 (T-08 — 400 본문 직접 노출 금지, BE 본문 노출 0 원칙).
  const [serverRejected, setServerRejected] = useState(false);

  const saveSettings = useMutation({
    mutationFn: (days: number) => saveSettingsApi({ 'retention.days': days }),
    onSuccess: (data: SettingsResponse) => {
      // 응답 = 갱신 후 GET 동일 형태 (설계 §5.2) → 캐시 직접 반영으로 dirty 즉시 해제 (C-02 재비활성).
      queryClient.setQueryData(['settings'], data);
      setRawInput(null); // prefill 모드 복귀 — 입력 표시값 = 갱신된 저장값
      setServerRejected(false);
      toast.success('설정을 저장했어요.'); // T-09
    },
    onError: () => {
      // E-01 서버 400 (범위 외/비정수) — T-08 인라인 동일 위치 (서버가 최종 거부자, BL-07).
      setServerRejected(true);
    },
  });

  // 표시값: 사용자가 입력 중이면 raw, 아니면 저장값 prefill.
  const displayValue = rawInput ?? (savedRetention !== undefined ? String(savedRetention) : '');
  // 빈 문자열 = NaN (Number('') === 0 함정 회피) — Number.isInteger(NaN) = false 로 무효 처리.
  const retentionInput = displayValue.trim() === '' ? Number.NaN : Number(displayValue);

  // planner §8.1.1 파생 상태 — 조건식 그대로 (코드 앵커, 설계 §3.2.3).
  const isValidRetention =
    Number.isInteger(retentionInput) &&
    retentionInput >= RETENTION_MIN &&
    retentionInput <= RETENTION_MAX; // RETENTION_MAX = 서버와 동일값 3650 (SSOT 는 서버 스키마)
  const canSaveRetention =
    isValidRetention &&
    retentionInput !== savedRetention && // dirty 일 때만
    !saveSettings.isPending;

  // T-08 노출: (blur 후 무효) 또는 (서버 400). 입력이 다시 유효해지면 서버 거부 표시도 해제.
  const showValidationError = (blurred && !isValidRetention) || (serverRejected && !saveSettings.isPending);

  const handleSave = (): void => {
    if (!canSaveRetention) return;
    setServerRejected(false);
    saveSettings.mutate(retentionInput);
  };

  return (
    <section className="rounded-lg border border-stone-200 bg-white p-6">
      {/* T-04 */}
      <h2 className="text-base font-medium text-stone-900">Retention</h2>

      {settingsQuery.isError ? (
        // E-10 — 섹션 본문 대체: T-27 + 다시 시도 (ErrorState 의 error.message 미사용 — 고정 문구,
        // BE 본문 노출 0. Masking 섹션과 독립 — 한쪽 실패가 다른 쪽 차단 금지, UX §5.3).
        <div className="mt-4 flex flex-col items-start gap-3">
          <p className="text-sm text-stone-500">설정을 불러오지 못했어요.</p>
          <button
            type="button"
            onClick={() => void settingsQuery.refetch()}
            className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50"
          >
            다시 시도
          </button>
        </div>
      ) : (
        <div className="mt-4 space-y-4">
          <div>
            {/* T-05 — label htmlFor 연결 (a11y 의무) */}
            <label htmlFor="retention-days-input" className="block text-sm font-medium text-stone-900">
              보관 기간 (일)
            </label>
            <div className="mt-1.5 flex items-center gap-2">
              {settingsQuery.isLoading ? (
                // C-01 로딩 — 값 자리 스켈레톤 톤 (UX §5.3)
                <div aria-hidden className="h-9 w-24 animate-pulse rounded-md border border-stone-200 bg-stone-50" />
              ) : (
                <input
                  id="retention-days-input"
                  type="number"
                  inputMode="numeric"
                  min={RETENTION_MIN}
                  max={RETENTION_MAX}
                  value={displayValue}
                  onChange={(e) => {
                    setRawInput(e.target.value);
                    setServerRejected(false);
                  }}
                  onBlur={() => setBlurred(true)}
                  // C-01: settingsQuery.isLoading || saveSettings.isPending (planner §8.1 그대로)
                  disabled={settingsQuery.isLoading || saveSettings.isPending}
                  aria-invalid={showValidationError ? true : undefined}
                  aria-describedby={showValidationError ? 'retention-days-error' : undefined}
                  className="w-24 rounded-md border border-stone-200 px-3 py-1.5 text-sm text-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900 disabled:opacity-50"
                />
              )}
              {/* T-07 — C-02: disabled = !canSaveRetention */}
              <button
                type="button"
                onClick={handleSave}
                disabled={!canSaveRetention}
                className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
              >
                저장
              </button>
            </div>
            {showValidationError && (
              // T-08 — 보간 {min}=1, {max}=3650 (서버 SettingsRegistry 와 4표면 동일값, 설계 §2-B1)
              <p id="retention-days-error" className="mt-1.5 text-xs text-[var(--color-status-error)]">
                보관 기간은 {RETENTION_MIN}~{RETENTION_MAX} 사이의 정수여야 해요.
              </p>
            )}
          </div>

          {/* T-06 — D-05 사용자 노출 명문 */}
          <p className="text-xs text-stone-500">
            보관 기간이 지난 trace 는 매일 밤 자동 삭제돼요. 저장한 값이 서버 설정(yml)보다 우선해요.
          </p>

          <div className="border-t border-stone-200 pt-3 text-xs text-stone-500">
            {lastCleanupAt !== undefined &&
              (lastCleanupAt === 0 ? (
                // T-11 — 시드 0 분기 (PLAN §5-3: 값 0 = 이력 없음)
                <span>아직 자동 정리가 실행되지 않았어요.</span>
              ) : (
                // T-10 — 날짜+시간 (formatHms 는 시각만이라 부적합: cleanup 은 일 단위 과거 가능, UX §3.3)
                <span>마지막 cleanup 시각: {new Date(lastCleanupAt).toLocaleString()}</span>
              ))}
          </div>
        </div>
      )}
    </section>
  );
}
