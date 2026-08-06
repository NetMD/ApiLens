// [R21] 원격 계측 설정 화면 본체 — 카드 1(원격 지시) + 카드 2(알아 둘 것) + 카드 3(생성기).
//
// ⚠️ **신규 라우트가 아니다.** `?config={서비스이름}` 검색 파라미터로 같은 `/services` 경로 안에서
//    목록 ↔ 설정 화면을 바꾼다 (`?analyze=` 전례 완전 동형 — App.tsx / WebMvcConfig diff 0).
//    경로가 안 바뀌므로 화면 전환 시 포커스를 직접 옮긴다 (InstrumentAnalysis 전례 의무).
//
// R21/AC-02-1 (Plan verbatim): "4축 편집이 된다 — boolean 3축 각각 3상태 + gateExcludes 목록
// 편집(추가·삭제). PUT/GET/DELETE 3동작 전부 화면에서 가능." (US-02 — 이 라운드 본체)
// R21/AC-02-5 (Plan verbatim): "기준점 비단정 — 화면은 JVM 기동 -D 값을 모른다(server 미저장).
// '이 지시가 적용됩니다' 단정 문구 금지(W-1)." — 기동 -D 기준점은 계약에 없으므로 화면은 그 값을
// 판정하는 조건문을 아예 만들지 않는다. T-05 비단정 문구가 그 자리를 대신한다 (설계 §2.3).
// R21/AC-02-6 — 경고 3종(카드 2)은 docs 동작 원칙 절 verbatim (T-06·T-07·T-08 — 해요체 전환 금지,
// 카드 2 만 합니다체인 것은 docs 인용이라 의도된 톤 차이 — UX §9.3-6).
// R21/AC-02-7 — 404 = "지시 없음(정상)" 이 기본 화면 (P-R21-2 — API 층 null 변환, 재시도 구조 소멸).
//
// 화면 상태 4종 (설계 §2.2): A 설정 실재(GET 200) / B 빈 상태(404 → null) / C 401 / D 서비스 못
// 찾음(ActiveServices renderConfig 가 이 컴포넌트 진입 전에 분기 — 여기 도달하면 서비스는 실재).
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ServiceInfo } from '../../types/api';
import { ApiError } from '../../api/client';
import {
  deleteInstrumentConfig,
  getInstrumentConfig,
  putInstrumentConfig,
} from '../../api/instrumentConfig';
import {
  formsEqual,
  fromGetPayload,
  isEmptyDirective,
  toPutPayload,
} from '../../lib/instrument-config-directive';
import type { InstrumentConfigForm } from '../../lib/instrument-config-directive';
import { ErrorState } from '../ErrorState';
import { LoadingSkeleton } from '../LoadingSkeleton';
import { useToast } from '../useToast';
import { AxisDirectiveSelect } from './AxisDirectiveSelect';
import { ExcludeListEditor } from './ExcludeListEditor';
import { InstrumentOptionGeneratorCard } from './InstrumentOptionGeneratorCard';

/** G-18 — 서버 검증 규칙 선반영 (ServiceInstrumentConfigService 상수와 정합. 서버 400 문구 동일). */
const MAX_GATE_EXCLUDES_COUNT = 100;
const MAX_GATE_EXCLUDE_ITEM_LENGTH = 512;

interface Props {
  service: ServiceInfo;
  /** 목록으로 복귀 (주소에서 config 제거 + 눌렀던 행 버튼으로 포커스 복원). */
  onBack: () => void;
}

export function InstrumentConfigPanel({ service, onBack }: Props): ReactNode {
  const serviceName = service.name;
  const queryClient = useQueryClient();
  const toast = useToast();
  const headingRef = useRef<HTMLHeadingElement | null>(null);

  // 경로가 안 바뀌는 화면 전환이라 포커스를 직접 옮긴다 (InstrumentAnalysis 전례 — UX §8).
  useEffect(() => {
    headingRef.current?.focus();
  }, []);

  // 설계 §2.2 — 반환 InstrumentConfigPayload | null. null = 404(지시 없음·정상).
  // 캐싱: 진입 시 1회 + invalidate — 폴링·SSE 0 (전역 refetchOnWindowFocus:false + refetchInterval
  // 없음이라 편집 중 예기치 않은 재조회 경로 없음). 낙관적 업데이트 금지 (서버 확정 후 반영).
  const query = useQuery({
    queryKey: ['instrumentConfig', serviceName],
    queryFn: ({ signal }) => getInstrumentConfig(serviceName, signal),
  });

  // 폼 상태 — 초기값 = fromGetPayload(null) (빈 상태 폼). 재동기화는 query.data 가 새로 온
  // 시점만 (진입 1회 + 저장/철회 성공 invalidate). 서비스 전환 리셋은 key={serviceName} 몫.
  const [form, setForm] = useState<InstrumentConfigForm>(() => fromGetPayload(null));
  // gateExcludes 입력 검증 오류 실재 여부 (C-05 의 validationErrors 배선).
  const [hasInputError, setHasInputError] = useState(false);
  // PUT 400 (검증) — 서버 flat 문구 role=alert 인라인 (UX §7.1 — BE 본문 비노출 원칙의 명시된 예외).
  const [inlineError, setInlineError] = useState<string | null>(null);
  // 저장/철회 401 — 폼 유지 + 폼 위 ErrorState (입력 유실 방지 — 상태 C 동형).
  const [mutationAuthError, setMutationAuthError] = useState<ApiError | null>(null);
  // 철회 인라인 2단계 확인 (cleanup 전례 — 모달 아님: 재저장으로 회복 가능한 동작이라 강한 확인 불요).
  const [confirmingRevoke, setConfirmingRevoke] = useState(false);

  useEffect(() => {
    // 로딩 완료(404 포함 — null 도 데이터) 시점만 스냅샷으로 재동기화.
    if (query.data !== undefined) {
      setForm(fromGetPayload(query.data));
    }
  }, [query.data]);

  const saveMutation = useMutation({
    mutationFn: (payload: ReturnType<typeof toPutPayload>) =>
      putInstrumentConfig(serviceName, payload),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['instrumentConfig', serviceName] });
      setInlineError(null);
      setMutationAuthError(null);
      toast.success('설정을 저장했어요. 다음 기록 전송 응답부터 실려 가요.'); // U-29
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 401) {
        setMutationAuthError(err);
        return;
      }
      if (err instanceof ApiError && err.status === 400) {
        // 서버 flat 문구 (ApiError.message = body.error). 토스트는 사라져서 부적합 — 인라인.
        setInlineError(err.message);
        return;
      }
      toast.error('저장에 실패했어요. 잠시 후 다시 시도해 주세요.'); // U-31
    },
  });

  const revokeMutation = useMutation({
    mutationFn: () => deleteInstrumentConfig(serviceName),
    onSuccess: async () => {
      // 빈 상태 폼 복귀는 invalidate 로 스냅샷이 null 로 갱신되며 자동 (설계 §2.2).
      await queryClient.invalidateQueries({ queryKey: ['instrumentConfig', serviceName] });
      setConfirmingRevoke(false);
      setInlineError(null);
      setMutationAuthError(null);
      toast.success('저장된 지시를 철회했어요. agent 에 이미 적용된 값은 그대로예요.'); // U-30
    },
    onError: (err) => {
      setConfirmingRevoke(false);
      if (err instanceof ApiError && err.status === 401) {
        setMutationAuthError(err);
        return;
      }
      toast.error('철회에 실패했어요. 잠시 후 다시 시도해 주세요.'); // U-31
    },
  });

  // ── 컨트롤 파생 상태 (설계 §2.3 확정식의 코드 고정 — UX §7.2) ──────────────
  const mutating = saveMutation.isPending || revokeMutation.isPending; // 저장·철회 상호 배타
  const configExists = query.data != null; // null(404)·undefined(로딩) 모두 false — C-06 근거
  const snapshot = fromGetPayload(query.data ?? null);
  const isDirty = !formsEqual(form, snapshot);
  const emptyDirective = isEmptyDirective(form); // [S-117] 단일 술어 — 소비 3 (canSave/U-32/핸들러 방어)
  const canSave = !query.isLoading && !mutating && !hasInputError && isDirty && !emptyDirective; // C-05 (BL-09 확정 포함)
  const canRevoke = configExists && !mutating; // C-06
  const axisDisabled = query.isLoading || mutating; // C-02

  const handleSave = (): void => {
    if (isEmptyDirective(form)) return; // BL-09 방어 1줄 — canSave 와 같은 술어 소비 (drift 0, 설계 §11-4)
    saveMutation.mutate(toPutPayload(form));
  };

  const updateForm = (patch: Partial<InstrumentConfigForm>): void => {
    setForm((prev) => ({ ...prev, ...patch }));
  };

  // ── 상태 C — GET 401: ErrorState 단독 (폼 미표시. 기존 401 분기 재사용 — 신규 문구 0, T-10) ──
  if (query.isError && query.error instanceof ApiError && query.error.status === 401) {
    return (
      <div className="space-y-4">
        <BackButton onBack={onBack} />
        <ErrorState error={query.error} />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* ── 머리 ── */}
      <div className="space-y-2">
        <BackButton onBack={onBack} />
        <div className="flex items-baseline justify-between gap-4">
          <h1
            ref={headingRef}
            tabIndex={-1}
            className="text-lg font-medium text-stone-900 outline-none"
          >
            {/* U-25 */}
            {serviceName} 계측 설정
          </h1>
          <p className="text-xs text-stone-500">
            {/* T-23 — 버전 라벨 정직화 (분석 화면·Services 표와 동일 문구 통일). */}
            agent 버전 (마지막 확인 시점){' '}
            {service.agentVersion === null ? (
              <span className="text-stone-300">—</span>
            ) : (
              <span className="font-mono text-stone-900">{service.agentVersion}</span>
            )}
          </p>
        </div>
      </div>

      {/* GET 로딩 (404 도착 = 로딩 완료 — 빈 상태·편집 활성, C-02 주의). */}
      {query.isLoading && <LoadingSkeleton variant="list" />}

      {/* GET 그 외 오류 — ErrorState + [Retry] (기존 규격. 404 는 여기 오지 않는다 — null 성공 값). */}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}

      {!query.isLoading && !query.isError && (
        <>
          {/* 상태 B — 빈 상태(404) 안내. 같은 폼이 그대로 첫 설정 경로다 (별도 빈 박스로 대체 금지). */}
          {!configExists && (
            <p role="status" className="text-xs text-stone-500">
              {/* T-09 */}
              아직 저장된 지시가 없어요 (정상) — 처음 쓰는 서비스라면 여기서 바로 저장하면 돼요.
            </p>
          )}

          {/* 저장/철회 401 — 폼 유지 + 폼 위 ErrorState 동형 (입력 유실 방지). */}
          {mutationAuthError !== null && <ErrorState error={mutationAuthError} />}

          {/* ── 카드 1 · 원격 지시 (U-40) ── */}
          <section className="space-y-4 rounded-lg border border-stone-200 bg-white p-6">
            <h2 className="text-base font-medium text-stone-900">원격 지시</h2>
            {/* T-05 — 기준점 비단정 (판정 표현 = RemoteConfigGate javadoc 차용). */}
            <p className="text-xs text-stone-500">
              이 화면은 JVM 을 시작할 때 준 -D 값(기준점)을 알지 못해요. 지시가 그대로 적용된다고
              단정하지 않아요 — 줄이는 지시와 기동값으로 되돌리는 지시는 적용되고, 기동값을 넘는
              확대 지시는 agent 가 버려요.
            </p>

            <div className="divide-y divide-stone-200">
              <div className="py-4 first:pt-0">
                <AxisDirectiveSelect
                  label="JDBC 파라미터 캡처"
                  axisKey="captureParams"
                  // T-02
                  direction="줄이기 = JDBC 파라미터 캡처를 끄는 쪽이에요."
                  value={form.captureParams}
                  onChange={(next) => updateForm({ captureParams: next })}
                  disabled={axisDisabled}
                  // T-14 — 구조적 불가 안내 (상시 표시 — 게재 확정, UX §9.1).
                  note="참고: -Dapilens.jdbc.capture-params=false 로 시작한 JVM 은 계측 코드 자체가 심어지지 않아, 원격 지시로 다시 살릴 수 없어요(구조적으로 불가)."
                />
              </div>
              <div className="py-4">
                <AxisDirectiveSelect
                  label="JDBC 결과 캡처"
                  axisKey="captureResultSet"
                  // T-03
                  direction="줄이기 = JDBC 결과(row) 캡처를 끄는 쪽이에요."
                  value={form.captureResultSet}
                  onChange={(next) => updateForm({ captureResultSet: next })}
                  disabled={axisDisabled}
                />
              </div>
              <div className="py-4">
                <AxisDirectiveSelect
                  label="진입점 없는 흐름 만들지 않기"
                  axisKey="requireEntryRoot"
                  // T-04 — 방향 반전 명시 (색만으로 전달 금지 — ⚠ 기호 + 문장, UX §8).
                  direction={
                    <>
                      <span aria-hidden>⚠ </span>이 축은 방향이 반대예요 — 줄이기 =
                      진입점(controller)에서 시작하지 않는 흐름을 만들지 않는 쪽이에요. 배치
                      워커(@Scheduled/@Async) 흐름이 통째로 사라져요.
                    </>
                  }
                  value={form.requireEntryRoot}
                  onChange={(next) => updateForm({ requireEntryRoot: next })}
                  disabled={axisDisabled}
                />
              </div>
              <div className="space-y-2 py-4 last:pb-0">
                <p className="text-sm font-medium text-stone-900">
                  개별 제외 목록{' '}
                  <span className="font-normal lowercase text-stone-400">(gateExcludes)</span>
                </p>
                <ExcludeListEditor
                  items={form.gateExcludes}
                  onItemsChange={(next) => updateForm({ gateExcludes: next })}
                  disabled={axisDisabled}
                  removeDisabled={mutating}
                  placeholder="클래스 전체 이름 입력"
                  inputLabel="개별 제외 클래스 추가"
                  maxItems={MAX_GATE_EXCLUDES_COUNT}
                  maxItemLength={MAX_GATE_EXCLUDE_ITEM_LENGTH}
                  showCounterLine
                  onInputErrorChange={setHasInputError}
                />
                {/* U-43 — 상시 안내 (조건 분기 0 — T-20 전례 철학. 설계 §4.2-(b) 확정 문안). */}
                <p className="text-xs text-stone-500">
                  목록을 전부 비워 저장하면 이 축은 &apos;지시 없음&apos; 이 돼요 — agent 에 이미
                  적용된 제외 목록은 그대로예요(철회와 같아요). 지금 걸린 제외를 풀려면 남길
                  항목만으로 저장하거나 JVM 을 다시 시작해 주세요.
                </p>
              </div>
            </div>

            {/* PUT 400 (검증) — 서버 flat 문구 role=alert 인라인. */}
            {inlineError !== null && (
              <p role="alert" className="text-xs text-[var(--color-status-error)]">
                {inlineError}
              </p>
            )}

            {/* U-32 — BL-09 유도 (설정 실재 + 전부 지시 없음으로 되돌린 경우에만 — UX §4.5). */}
            {configExists && emptyDirective && (
              <p role="status" className="text-xs text-stone-500">
                모든 축이 &apos;지시 없음&apos; 이고 목록이 비어 있어요 — 이 상태는 저장 대신
                [철회] 로 만들어요.
              </p>
            )}

            <div className="flex items-center justify-end gap-2">
              {confirmingRevoke ? (
                <>
                  {/* U-28 — 인라인 2단계 확인 (cleanup 전례). */}
                  <span className="text-xs text-stone-500">
                    저장된 지시를 철회할까요? agent 에 이미 적용된 값은 되돌아가지 않아요.
                  </span>
                  <button
                    type="button"
                    onClick={() => revokeMutation.mutate()}
                    disabled={!canRevoke}
                    className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                  >
                    {revokeMutation.isPending ? '철회 중…' : '확인'}
                  </button>
                  <button
                    type="button"
                    onClick={() => setConfirmingRevoke(false)}
                    disabled={mutating}
                    className="rounded-md border border-stone-200 bg-white px-3 py-1.5 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                  >
                    취소
                  </button>
                </>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmingRevoke(true)}
                  // C-06 — canRevoke = configExists && !mutating (404 빈 상태에서는 철회만 비활성).
                  disabled={!canRevoke}
                  className="rounded-md border border-stone-200 bg-white px-4 py-2 text-sm font-medium text-stone-900 hover:bg-stone-50 disabled:opacity-50"
                >
                  {/* U-28 */}
                  철회
                </button>
              )}
              <button
                type="button"
                onClick={handleSave}
                // C-05 — canSave (BL-09 확정: 전부 지시 없음 + 빈 목록이면 저장 비활성 + 철회 유도).
                disabled={!canSave}
                className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
              >
                {/* U-27 */}
                {saveMutation.isPending ? '저장 중…' : '저장'}
              </button>
            </div>
          </section>

          {/* ── 카드 2 · 알아 둘 것 (U-39) — T-06·T-07·T-08 verbatim (docs 인용 — 합니다체 의도) ── */}
          <section className="space-y-3 rounded-lg border border-stone-200 bg-white p-6">
            <h2 className="text-base font-medium text-stone-900">
              알아 둘 것 — 이 설정이 동작하는 방식 3가지
            </h2>
            <ul className="list-disc space-y-2 pl-5 text-sm text-stone-600">
              <li>
                {/* T-06 (V — 변형 금지) */}
                <strong>원격으로 끈 것은 영구 설정이 아닙니다</strong> — 게이트 값은 메모리에만 있고
                JVM 재시작 시 시작 -D 값으로 되돌아갑니다. 영구로 만들려면 -D 를 바꿔 재시작하세요.
              </li>
              <li>
                {/* T-07 (V 첫 문장 + 후속 문장 포함 확정) */}
                <strong>전파는 기록 전송 응답에 실려 옵니다</strong> — 트래픽이 없는 서비스, 수신
                일시정지 중, 또는 억제 옵션으로 기록이 급감한 서비스는 적용이 늦어질 수 있습니다.
                늦는 방향은 항상 &quot;예전 계측 상태가 잠시 더 유지되는&quot; 쪽이며, 기록이
                흐르기 시작하면 자동 적용됩니다(급하면 JVM 재시작 = 시작 -D 값 복원).
              </li>
              <li>
                {/* T-08 (V 첫 문장 + 복귀 안내 화면어 확정) */}
                <strong>철회(DELETE)해도 agent 에 이미 적용된 값은 되돌아가지 않습니다</strong> —
                응답에 설정이 더 이상 실리지 않을 뿐입니다. 되돌리려면 &apos;기동값으로
                되돌리기&apos; 를 저장하거나 JVM 을 재시작하세요.
              </li>
            </ul>
          </section>

          {/* ── 카드 3 · -D 옵션 문자열 생성기 (§4.6 — 카드 1 폼과 연동 없음) ── */}
          <InstrumentOptionGeneratorCard />
        </>
      )}
    </div>
  );
}

/** [← Services 목록] — 어떤 이유로도 비활성 금지 (C-09 — C-22 전례. 진행 중 mutation 은 그대로 완료).
 *  미저장 변경 상태에서도 되묻지 않고 즉시 나간다 (Setup 취소 전례 — UX §4.2). */
function BackButton({ onBack }: { onBack: () => void }): ReactNode {
  return (
    <button
      type="button"
      onClick={onBack}
      className="rounded px-2 py-1 text-xs text-stone-500 hover:bg-stone-100 hover:text-stone-900"
    >
      {/* U-26 */}
      ← Services 목록
    </button>
  );
}
