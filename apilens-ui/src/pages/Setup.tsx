// [Phase H] U1 — Setup wizard 4단계 페이지.
//
// 사용자 명시 비협상 결정 인용:
//   D-01: wizard 재접근 가능 (헤더 [+] / 좌측 메뉴 Services [+] 두 경로)
//   D-02: 등록 경로 A (wizard source='wizard')
//   D-04: skip 가능 — setup_completed=1 마킹, 자동 재출현 X
//   V-USER-H1: 라벨 명사형 / placeholder·안내 해요체 / 에러 명사형 짧게
//   SH-02: [복사] 성공 = 버튼 라벨 변경 + toast 둘 다
//   SH-06: navigate 시 searchParams.toString() 동봉 (R3 회귀 차단)
//   SH-11: wizard 진행 중 새로고침 = 처음부터 (URL state 안 박음 — useState 단일)
//   SH-14: code 박스 select-all (clipboard 권한 거부 fallback)
//   SH-15: input focus:ring-1 focus:ring-stone-900 의무
//   SH-16: skip / 완료 둘 다 queryClient.invalidateQueries(['setup','state']) 호출
//
// step 진행 상태를 URL search 의 ?step=1~4 에 박는다 (요청서 명시 R3 회귀 가드).
// 입력 값 (serverUrl/serviceName/captureParams/captureResultSet) 은 useState — SH-11.
import { useId, useRef, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { completeSetup } from '../api/setup';
import type { SetupCompleteRequest } from '../types/api';
import {
  buildAgentOptionPreview,
  buildEnvSnippetPreview,
  runEnvNote,
  RUN_ENVS,
  type RunEnv,
} from '../lib/agent-option-builder';
import { useSearchPreservingNavigate } from '../hooks/useSearchPreservingNavigate';
import { useAgentJarPath } from '../hooks/useAgentJarPath';
import { Stepper } from '../components/Stepper';
import { Toggle } from '../components/Toggle';
import { Modal } from '../components/Modal';
import { useToast } from '../components/useToast';

const STEPS = ['Server URL', 'Service Name', 'Capture Options', 'JVM 옵션'] as const;
const SERVICE_NAME_RE = /^[A-Za-z0-9_-]+$/;

type StepNum = 1 | 2 | 3 | 4;

function parseStep(raw: string | null): StepNum {
  if (raw === '2') return 2;
  if (raw === '3') return 3;
  if (raw === '4') return 4;
  return 1;
}

export function Setup(): ReactNode {
  const [searchParams, setSearchParams] = useSearchParams();
  const nav = useSearchPreservingNavigate();
  const queryClient = useQueryClient();
  const toast = useToast();

  // step 만 URL ?step=1~4 반영 (요청서 명시 R3 회귀 가드 — 새로고침/뒤로가기 일관성).
  // 입력 값은 useState (SH-11 단순성 우선).
  const step = parseStep(searchParams.get('step'));
  // [R10] AC-03-1 (D-H10-05 비협상 — V-USER-R10-01 sign-off) — window.location.origin default 박힘.
  // SH-10 정합 — placeholder 보조 강등 (값 지웠을 때만 표시). SH-14 검증 시점 영향 0.
  // 회귀 가드 grep (반대): useState('') (serverUrl 위치) 0 hit.
  const [serverUrl, setServerUrl] = useState(() => window.location.origin);
  const [serviceName, setServiceName] = useState('');
  const [captureParams, setCaptureParams] = useState(true);
  const [captureResultSet, setCaptureResultSet] = useState(false);

  // [R10] AC-05-10 (D-H10-01 비협상) — Step 4 진입 시 path 주입.
  // 모든 Step 에서 hook 호출 (rules-of-hooks). useQuery 가 staleTime: Infinity 라 1회만 호출.
  const { path: agentJarPath, isLoading: agentJarLoading } = useAgentJarPath();

  // blur 이후에만 에러 표시 — 첫 입력 중에는 짜증나게 띄우지 않음 (UX §6.2).
  const [serverUrlBlurred, setServerUrlBlurred] = useState(false);
  const [serviceNameBlurred, setServiceNameBlurred] = useState(false);

  // [복사] 버튼 success 일시 라벨 변경 상태.
  const [copied, setCopied] = useState(false);

  // Step 4 실행 환경 탭 (java -jar / Maven / Gradle / Docker). 기본 java -jar.
  const [runEnv, setRunEnv] = useState<RunEnv>('java');

  // skip confirm 모달.
  const [skipConfirmOpen, setSkipConfirmOpen] = useState(false);
  const skipCancelRef = useRef<HTMLButtonElement | null>(null);

  const setStep = (next: StepNum): void => {
    setSearchParams(
      (prev) => {
        const p = new URLSearchParams(prev);
        if (next === 1) {
          p.delete('step');
        } else {
          p.set('step', String(next));
        }
        return p;
      },
      { replace: true },
    );
  };

  // 유효성 ───────────────────────────────────────────────────────
  const serverUrlError = ((): string | null => {
    if (serverUrl.trim() === '') return 'URL 입력 필요';
    if (!(serverUrl.startsWith('http://') || serverUrl.startsWith('https://'))) {
      return 'URL 형식 오류 (http:// 또는 https://)';
    }
    return null;
  })();
  const serviceNameError = ((): string | null => {
    if (serviceName.trim() === '') return '이름 입력 필요';
    if (!SERVICE_NAME_RE.test(serviceName)) return '이름 형식 오류';
    return null;
  })();
  const canNextFromStep1 = serverUrlError === null;
  const canNextFromStep2 = serviceNameError === null;

  // JVM 옵션 미리보기 — Step 3 / 4 둘 다 사용.
  // [R10] AC-05-10 — agentJarPath 주입 (D-H10-01 비협상). null 시 FALLBACK_JAR_PATH 사용.
  const jvmOption = buildAgentOptionPreview({
    serviceName,
    serverUrl,
    captureParams,
    captureResultSet,
    agentJarPath,
  });

  // Step 4 — 선택한 실행 환경에 맞춘 부착 스니펫. 토큰은 jvmOption 과 동일,
  // 환경(java -jar / Maven / Gradle / Docker)에 맞게 감싼 형태.
  const envSnippet = buildEnvSnippetPreview(runEnv, {
    serviceName,
    serverUrl,
    captureParams,
    captureResultSet,
    agentJarPath,
  });

  // Setup 완료 mutation (skip + 완료 공통 — POST /v1/setup/complete).
  const completeMutation = useMutation({
    mutationFn: async (body: SetupCompleteRequest) => completeSetup(body),
    onSuccess: async (_data, vars) => {
      // SH-16 — 두 경로 (skip / 완료) 모두 invalidate.
      await queryClient.invalidateQueries({ queryKey: ['setup', 'state'] });
      await queryClient.invalidateQueries({ queryKey: ['services'] });
      await queryClient.invalidateQueries({ queryKey: ['services', 'detailed'] });
      setSkipConfirmOpen(false);
      if (vars.services && vars.services.length > 0) {
        // [R10] AC-02-1 (D-H10-02 경로 A 비협상) — wizard 가 박은 service 를 dashboard 가 자동 선택.
        // SH-06 정합 — useSearchPreservingNavigate 가 기존 search 위에 덮어쓰기.
        // 회귀 가드 grep (반대): onSuccess 안에 nav('/') 단독 패턴 (services > 0 분기 없이) 0 hit.
        toast.success('Setup 이 완료됐어요. agent 부착 후 첫 trace 를 기다려 주세요');
        const firstService = vars.services[0];
        if (firstService) {
          nav('/', { search: { service: firstService.name } });
        } else {
          nav('/');
        }
      } else {
        // D-04 비협상: skip 후 setup_completed=1, 자동 재출현 없음.
        // SH-06: navigate 시 searchParams.toString() 동봉 (R3 회귀 차단).
        // [R10] D-H10-02 verbatim — skip 경로 (services=[]) 는 service 박지 않음.
        toast.success('Setup 가이드는 docs/setup.md 를 참고해 주세요');
        nav('/');
      }
    },
    onError: (_err, vars) => {
      if (vars.services && vars.services.length > 0) {
        toast.error('Setup 완료 실패 — 잠시 후 다시 시도해 주세요');
      } else {
        toast.error('건너뛰기 실패 — 잠시 후 다시 시도해 주세요');
      }
    },
  });

  const isCompleting = completeMutation.isPending;

  // 핸들러 ───────────────────────────────────────────────────────
  const handleNext = (e?: FormEvent): void => {
    if (e) e.preventDefault();
    if (step === 1 && canNextFromStep1) setStep(2);
    else if (step === 2 && canNextFromStep2) setStep(3);
    else if (step === 3) setStep(4);
  };
  const handlePrev = (): void => {
    if (step === 2) setStep(1);
    else if (step === 3) setStep(2);
    else if (step === 4) setStep(3);
  };

  const handleComplete = (): void => {
    // D-01/D-02 비협상: wizard 등록 경로 A (source='wizard')
    // SH-02: 복사 success = 버튼 라벨 변경 + toast 둘 다
    // SH-16: completeSetup 성공 후 queryClient.invalidateQueries(['setup','state'])
    completeMutation.mutate({
      serverUrl,
      services: [{ name: serviceName }],
    });
  };

  const handleSkipConfirm = (): void => {
    // D-04 비협상: skip 후 setup_completed=1, 자동 재출현 없음.
    completeMutation.mutate({
      serverUrl,
      services: [],
    });
  };

  // 취소 = wizard 를 즉시 나간다 (대시보드로). 건너뛰기와 달리 setup_completed 를
  // 마킹하지 않으므로 first-run 가드/재진입에 영향 0. confirm 안 띄움 — 취소는
  // 그 자체가 사용자 결정이므로 되묻지 않는다 (건너뛰기만 confirm 모달 유지).
  // SH-06 정합 — nav 가 route 경계에서 step 등 route-local search 를 제거.
  const handleCancel = (): void => {
    nav('/');
  };

  const handleCopy = async (): Promise<void> => {
    // 현재 선택된 실행 환경 스니펫을 복사.
    if (envSnippet === '') return;
    try {
      await navigator.clipboard.writeText(envSnippet);
      // SH-02 — 버튼 라벨 + toast 둘 다.
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2000);
      toast.success('붙여넣기용으로 복사했어요');
    } catch {
      toast.error('복사 실패 — 박스 내용을 직접 선택해 복사해 주세요');
    }
  };

  // step 4 진입 가능 가드 — 직접 URL 로 ?step=4 진입 후 입력값 빈 경우 회피.
  // canSubmitStep4 가 false 면 [완료] 버튼 disabled.
  const canSubmitStep4 =
    !isCompleting &&
    serverUrlError === null &&
    serviceNameError === null &&
    jvmOption !== '';

  return (
    <div className="flex h-full flex-col bg-stone-50">
      {/* 헤더 — wizard 전용 (Dashboard 컨트롤 안 노출, 좌측 메뉴는 보임 + 우상단 건너뛰기). */}
      <header className="flex h-14 items-center justify-between border-b border-stone-200 bg-white px-6">
        <div className="flex items-center gap-3">
          <span className="text-base font-semibold text-stone-900">ApiLens</span>
          <span className="text-xs text-stone-500">v0.1</span>
        </div>
        {/* 우상단 — 취소(즉시 나가기, confirm 없음) / 건너뛰기(confirm 후 setup 완료 마킹). */}
        <div className="flex items-center gap-4">
          <button
            type="button"
            onClick={handleCancel}
            disabled={isCompleting}
            className="text-sm text-stone-500 hover:text-stone-900 disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="취소"
          >
            취소
          </button>
          <button
            type="button"
            onClick={() => setSkipConfirmOpen(true)}
            disabled={isCompleting}
            className="text-sm text-stone-500 hover:text-stone-900 disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="건너뛰기"
          >
            건너뛰기 →
          </button>
        </div>
      </header>

      {/* 본문 */}
      <main className="flex-1 overflow-auto px-6 py-8">
        <div className="mx-auto max-w-2xl space-y-8">
          {/* Stepper */}
          <div className="px-2">
            <Stepper current={step} steps={STEPS as unknown as string[]} />
          </div>

          {/* Step content card */}
          <div className="rounded-lg border border-stone-200 bg-white p-8">
            {step === 1 && (
              <Step1
                value={serverUrl}
                onChange={setServerUrl}
                onBlur={() => setServerUrlBlurred(true)}
                error={serverUrlBlurred ? serverUrlError : null}
                onEnter={() => canNextFromStep1 && handleNext()}
              />
            )}
            {step === 2 && (
              <Step2
                value={serviceName}
                onChange={setServiceName}
                onBlur={() => setServiceNameBlurred(true)}
                error={serviceNameBlurred ? serviceNameError : null}
                onEnter={() => canNextFromStep2 && handleNext()}
              />
            )}
            {step === 3 && (
              <Step3
                captureParams={captureParams}
                captureResultSet={captureResultSet}
                onChangeParams={setCaptureParams}
                onChangeResultSet={setCaptureResultSet}
              />
            )}
            {step === 4 && (
              <Step4
                snippet={envSnippet}
                runEnv={runEnv}
                onSelectEnv={setRunEnv}
                copied={copied}
                onCopy={() => void handleCopy()}
                agentJarPath={agentJarPath}
                agentJarLoading={agentJarLoading}
              />
            )}
          </div>

          {/* footer buttons */}
          <div className="flex items-center justify-between">
            <button
              type="button"
              onClick={handlePrev}
              disabled={step === 1 || isCompleting}
              className="rounded-md border border-stone-200 bg-white px-4 py-2 text-sm text-stone-900 hover:bg-stone-50 disabled:cursor-not-allowed disabled:opacity-50"
            >
              이전
            </button>
            {step < 4 && (
              <button
                type="button"
                onClick={() => handleNext()}
                disabled={
                  (step === 1 && !canNextFromStep1) ||
                  (step === 2 && !canNextFromStep2)
                }
                className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                다음
              </button>
            )}
            {step === 4 && (
              <button
                type="button"
                onClick={handleComplete}
                disabled={!canSubmitStep4}
                className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                완료
              </button>
            )}
          </div>
        </div>
      </main>

      {/* Skip confirm 모달 */}
      <Modal
        open={skipConfirmOpen}
        onClose={() => !isCompleting && setSkipConfirmOpen(false)}
        title="Setup 건너뛰기"
        initialFocusRef={skipCancelRef}
      >
        <p>
          Setup 을 건너뛰시겠어요? <code className="font-mono text-stone-900">docs/setup.md</code>{' '}
          를 참고해 직접 옵션을 만들 수 있어요
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <button
            ref={skipCancelRef}
            type="button"
            onClick={() => setSkipConfirmOpen(false)}
            disabled={isCompleting}
            className="rounded-md border border-stone-200 bg-white px-4 py-2 text-sm text-stone-900 hover:bg-stone-50 disabled:opacity-50"
          >
            취소
          </button>
          <button
            type="button"
            onClick={handleSkipConfirm}
            disabled={isCompleting}
            className="rounded-md bg-stone-900 px-4 py-2 text-sm font-medium text-white hover:bg-stone-700 disabled:opacity-50"
          >
            건너뛰기
          </button>
        </div>
      </Modal>
    </div>
  );
}

// ── Step 1: Server URL ────────────────────────────────────────────
interface Step1Props {
  value: string;
  onChange: (next: string) => void;
  onBlur: () => void;
  error: string | null;
  onEnter: () => void;
}
function Step1({ value, onChange, onBlur, error, onEnter }: Step1Props): ReactNode {
  const id = useId();
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="block text-sm font-medium text-stone-900">
        Server URL
      </label>
      <input
        id={id}
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            onEnter();
          }
        }}
        placeholder="http://your-apilens-host:8765"
        // SH-15 — focus ring 의무
        className="h-10 w-full rounded-md border border-stone-200 px-3 font-mono text-sm focus:border-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900"
        aria-invalid={error !== null}
      />
      <p className="text-xs text-stone-500">
        운영망에서는 사용자 앱이 접근 가능한 IP/hostname 을 입력해 주세요
      </p>
      {error !== null && (
        <p role="alert" className="text-xs text-[var(--color-status-error)]">
          {error}
        </p>
      )}
    </div>
  );
}

// ── Step 2: Service Name ──────────────────────────────────────────
interface Step2Props {
  value: string;
  onChange: (next: string) => void;
  onBlur: () => void;
  error: string | null;
  onEnter: () => void;
}
function Step2({ value, onChange, onBlur, error, onEnter }: Step2Props): ReactNode {
  const id = useId();
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="block text-sm font-medium text-stone-900">
        Service Name
      </label>
      <input
        id={id}
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            onEnter();
          }
        }}
        placeholder="my-api"
        className="h-10 w-full rounded-md border border-stone-200 px-3 font-mono text-sm focus:border-stone-900 focus:outline-none focus:ring-1 focus:ring-stone-900"
        aria-invalid={error !== null}
      />
      {/* [R10] AC-04-2 (D-H10-06 비협상 — V-USER-R10-02 sign-off, 해요체) — 1차 안내. */}
      {/* 회귀 가드 grep (반대): R9 잔존 1차 안내 카피 0 hit (잔존 차단). */}
      <p className="text-xs text-stone-500">
        ApiLens 가 모니터링할 사용자 앱(서비스/시스템) 의 이름이에요
      </p>
      {/* [R10] AC-04-3 (D-H10-06 비협상) — 2차 보조 안내 + 예시 3개. */}
      <p className="text-xs text-stone-400">
        영문/숫자/하이픈/언더스코어. 예: my-api, order-service, vams
      </p>
      {error !== null && (
        <p role="alert" className="text-xs text-[var(--color-status-error)]">
          {error}
        </p>
      )}
    </div>
  );
}

// ── Step 3: Capture Options ───────────────────────────────────────
interface Step3Props {
  captureParams: boolean;
  captureResultSet: boolean;
  onChangeParams: (next: boolean) => void;
  onChangeResultSet: (next: boolean) => void;
}
function Step3({
  captureParams,
  captureResultSet,
  onChangeParams,
  onChangeResultSet,
}: Step3Props): ReactNode {
  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium text-stone-900">Capture Options</h3>
      <p className="text-xs text-stone-500">
        원하는 옵션만 켜 두세요. 나중에 변경할 수 있어요
      </p>
      <div className="divide-y divide-stone-200 rounded-md border border-stone-200">
        <div className="px-3">
          <Toggle
            id="capture-params"
            label="JDBC 파라미터 캡처"
            description="JDBC PreparedStatement 의 파라미터 값을 기록해요"
            checked={captureParams}
            onChange={onChangeParams}
          />
        </div>
        <div className="px-3">
          <Toggle
            id="capture-resultset"
            label="JDBC ResultSet 캡처"
            description="DB 조회 결과 row 의 일부를 기록해요. payload 가 커질 수 있어요"
            checked={captureResultSet}
            onChange={onChangeResultSet}
          />
        </div>
      </div>
    </div>
  );
}

// ── Step 4: JVM 옵션 박스 + 복사 + 안내 ─────────────────────────────
interface Step4Props {
  /** 선택된 실행 환경의 부착 스니펫 (입력 부족 시 빈 문자열). */
  snippet: string;
  runEnv: RunEnv;
  onSelectEnv: (env: RunEnv) => void;
  copied: boolean;
  onCopy: () => void;
  /** [R10] AC-05-11 — server 자동 추출 절대경로. null 시 fallback 경고 표시. */
  agentJarPath: string | null;
  /** [R10] AC-05-11 — true 시 path 분기 표시 보류 (false positive 회피). */
  agentJarLoading: boolean;
}
function Step4({
  snippet,
  runEnv,
  onSelectEnv,
  copied,
  onCopy,
  agentJarPath,
  agentJarLoading,
}: Step4Props): ReactNode {
  return (
    <div className="space-y-3">
      <h3 className="text-sm font-medium text-stone-900">실행 환경별 부착</h3>
      <p className="text-xs text-stone-500">
        자기 앱 실행 방식을 고르면 그에 맞는 부착법이 나와요. 붙여넣고 재기동해 주세요
      </p>

      {/* 실행 환경 탭 (java -jar / Maven / Gradle / Docker). */}
      <div
        role="tablist"
        aria-label="실행 환경"
        className="inline-flex flex-wrap gap-0.5 rounded-md border border-stone-200 bg-stone-50 p-0.5"
      >
        {RUN_ENVS.map((e) => {
          const active = runEnv === e.id;
          return (
            <button
              key={e.id}
              type="button"
              role="tab"
              aria-selected={active}
              onClick={() => onSelectEnv(e.id)}
              className={
                'rounded px-3 py-1 text-xs font-medium transition-colors ' +
                (active
                  ? 'bg-white text-stone-900 shadow-sm'
                  : 'text-stone-500 hover:text-stone-900')
              }
            >
              {e.label}
            </button>
          );
        })}
      </div>

      <div className="relative">
        {/* SH-14 — code 박스 select-all (clipboard 권한 거부 fallback). 멀티라인 보존. */}
        <code
          className="block max-h-60 select-all overflow-auto whitespace-pre-wrap break-all rounded-md bg-stone-900 p-4 pr-20 font-mono text-xs leading-relaxed text-stone-50"
          aria-label="부착 스니펫"
        >
          {snippet === '' ? (
            <span className="text-stone-400">
              Step 1·2 의 입력값이 있어야 스니펫이 생성돼요
            </span>
          ) : (
            snippet
          )}
        </code>
        <button
          type="button"
          onClick={onCopy}
          disabled={snippet === ''}
          className="absolute right-2 top-2 rounded bg-stone-700 px-2 py-1 text-xs text-stone-50 hover:bg-stone-600 disabled:cursor-not-allowed disabled:opacity-50"
          aria-label="스니펫 복사"
        >
          {copied ? '복사됨 ✓' : '복사'}
        </button>
      </div>

      {/* 환경별 주의사항 (운영자 오용 차단 — dogfooding 교훈). */}
      <p className="text-xs text-stone-500">{runEnvNote(runEnv)}</p>

      <p className="text-xs text-stone-500">
        재기동하면 잠시 뒤 /services 화면에 service 가 표시돼요
      </p>

      {/* [inter-pipeline] agent jar 다운로드 — 이 server 와 대상 앱이 다른 장비일 때
          (예: NAS 운영). server 의 절대경로는 다른 장비에서 못 쓰므로 받아서 옮겨야 함.
          같은 장비면 위 절대경로가 더 빠름 — 둘은 보완재. download attribute + 상대 URL
          로 대시보드를 띄운 바로 이 server 에서 받는다. */}
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1 pt-1">
        <a
          href="/v1/setup/agent-jar"
          download="apilens-agent.jar"
          className="inline-flex items-center gap-1 rounded-md border border-stone-300 bg-white px-3 py-1.5 text-xs font-medium text-stone-700 hover:bg-stone-50"
        >
          <span aria-hidden="true">⬇</span> agent jar 다운로드
        </a>
        <span className="text-xs text-stone-400">
          이 server 가 다른 장비에 있으면 받아서 대상 앱 장비로 옮긴 뒤, 위 경로 대신 받은
          파일 경로로 <code className="font-mono">-javaagent</code> 를 지정해 주세요
        </span>
      </div>

      {/* [R10] AC-05-11 (D-H10-01 비협상) — path=null fallback 경고 (NFR-02 + V-USER-H1 해요체). */}
      {/* agentJarLoading 시에는 표시 안 함 (false positive 회피). */}
      {!agentJarLoading && agentJarPath === null && (
        <p className="text-xs text-stone-400 mt-2">
          agent jar 자동 추출 안 됨 — server 재빌드 후 다시 시도해 주세요
        </p>
      )}
    </div>
  );
}
