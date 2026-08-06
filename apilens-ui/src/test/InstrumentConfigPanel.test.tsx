// [R21] 원격 계측 설정 화면 본체 — 상태 4종·컨트롤 매트릭스 역방향·404 변환 (P-R21-2).
//
// R21/AC-02-7 verbatim: "[§0.7-3 봉인] 404 = '지시 없음(정상)' 빈 상태가 기본 화면이다.
// 클라이언트 층(API 함수)에서 404 를 정상 빈 상태 값으로 바꾸고 그 경로만 재시도를 끈다
// (전역 retry: 1 — G-12). 빈 상태에서도 편집·첫 저장이 가능하다." (비협상 봉인)
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   returnsNullOn404WithoutThrow            — B-12: 404 → null (오류 아님)
//   keepsThrowOn401                         — B-13: 401 은 변환 금지 (throw 유지)
//   displaysEmptyStateFormOn404             — 빈 상태 = 같은 폼 + T-09, [철회]만 비활성 (C-06 역방향)
//   displaysSavedConfigOn200                — 역매핑 (reduce 선택 + 목록) + [철회] 활성
//   displaysRevokeGuidanceWhenAllNone       — B-24: U-32 + 저장 비활성 + 철회 활성 (BL-09)
//   displaysTokenErrorStateOn401            — 상태 C: ErrorState 단독 (폼 미표시)
//   keepsSingleRequestOn404                 — 404 는 성공 값이라 재시도 자체가 없다 (구조 검증)
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { useState } from 'react';
import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import { getInstrumentConfig } from '../api/instrumentConfig';
import { ExcludeListEditor } from '../components/instrument/ExcludeListEditor';
import { InstrumentConfigPanel } from '../components/instrument/InstrumentConfigPanel';
import { ToastProvider } from '../components/Toast';
import type { InstrumentConfigPayload, ServiceInfo } from '../types/api';

const SERVICE: ServiceInfo = {
  name: 'orders-api',
  registeredAt: 1_785_000_000_000,
  lastSeenAt: 1_785_344_000_000,
  source: 'auto',
  traceCount: 12_483,
  healthStatus: 'active',
  agentVersion: '0.6.0',
};

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 설정 GET 응답을 지정해 fetch 를 모사한다. 호출 기록은 반환 spy 로 단언. */
function mockConfigApi(respond: () => Response): ReturnType<typeof vi.spyOn> {
  return vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/instrument-config')) {
      return Promise.resolve(respond());
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
}

const NOT_FOUND_BODY = {
  error: '설정이 없습니다: 해당 서비스에 저장된 원격 계측 설정이 없습니다.',
};

function renderPanel(): void {
  // 전역 App.tsx 와 같은 retry: 1 을 그대로 둔다 — 404 가 성공 값이라 재시도가 안 생기는
  // "구조 성립" 을 실측하기 위해 (retry: false 로 가리지 않는다).
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MemoryRouter initialEntries={['/services?config=orders-api']}>
          <InstrumentConfigPanel service={SERVICE} onBack={() => undefined} />
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('getInstrumentConfig — 404 → null 변환 (P-R21-2)', () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(() => vi.restoreAllMocks());

  it('returnsNullOn404WithoutThrow — 404 는 오류가 아니라 null 값 (B-12)', async () => {
    mockConfigApi(() => jsonResponse(NOT_FOUND_BODY, 404));
    await expect(getInstrumentConfig('orders-api')).resolves.toBe(null);
  });

  it('keepsThrowOn401 — 401 은 변환 금지, ApiError 그대로 throw (B-13)', async () => {
    mockConfigApi(() => jsonResponse({ error: 'unauthorized' }, 401));
    await expect(getInstrumentConfig('orders-api')).rejects.toMatchObject({
      name: 'ApiError',
      status: 401,
    });
  });

  it('keepsThrowOn500 — 404 아닌 서버 오류도 그대로 throw (전역 규약 유지)', async () => {
    mockConfigApi(() => jsonResponse({ error: 'boom' }, 500));
    await expect(getInstrumentConfig('orders-api')).rejects.toBeInstanceOf(ApiError);
  });
});

describe('InstrumentConfigPanel — 화면 상태·컨트롤 매트릭스', () => {
  beforeEach(() => vi.restoreAllMocks());
  afterEach(() => vi.restoreAllMocks());

  it('displaysEmptyStateFormOn404 — 빈 상태(정상) 폼 + T-09, [철회]만 비활성 (C-06 역방향)', async () => {
    mockConfigApi(() => jsonResponse(NOT_FOUND_BODY, 404));
    renderPanel();
    // T-09 — 결핍 어휘 아님, "(정상)" 병기.
    expect(await screen.findByText(/아직 저장된 지시가 없어요 \(정상\)/)).toBeInTheDocument();
    // 404 도착 = 로딩 완료 — 편집 활성 (C-02). 첫 설정 경로가 여기다.
    const paramsGroup = screen.getByRole('group', { name: /JDBC 파라미터 캡처/ });
    expect(within(paramsGroup).getByRole('radio', { name: '지시 없음' })).toBeEnabled();
    expect(within(paramsGroup).getByRole('radio', { name: '지시 없음' })).toBeChecked();
    // C-06 — 철회할 것이 없으므로 [철회]만 비활성.
    expect(screen.getByRole('button', { name: '철회' })).toBeDisabled();
    // isDirty=false 라 저장도 자연 비활성 (U-32 는 이 경우 뜨지 않는다 — 설정 미실재).
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(
      screen.queryByText(/이 상태는 저장 대신 \[철회\] 로 만들어요/),
    ).not.toBeInTheDocument();
  });

  it('displaysSavedConfigOn200 — 저장된 지시 역매핑 + [철회] 활성 (상태 A)', async () => {
    const payload: InstrumentConfigPayload = {
      captureParams: false, // reduce
      gateExcludes: ['com.acme.mapper.NoisyMapper', 'com.acme.batch.OrderSyncJob'],
    };
    mockConfigApi(() => jsonResponse(payload));
    renderPanel();
    expect(await screen.findByText('com.acme.mapper.NoisyMapper')).toBeInTheDocument();
    expect(screen.getByText('com.acme.batch.OrderSyncJob')).toBeInTheDocument();
    const paramsGroup = screen.getByRole('group', { name: /JDBC 파라미터 캡처/ });
    expect(within(paramsGroup).getByRole('radio', { name: '줄이기' })).toBeChecked();
    // 부재 축은 none 역매핑.
    const entryGroup = screen.getByRole('group', { name: /진입점 없는 흐름 만들지 않기/ });
    expect(within(entryGroup).getByRole('radio', { name: '지시 없음' })).toBeChecked();
    // C-06 — 설정 실재 → [철회] 활성. 스냅샷과 동일 폼 → [저장] 비활성 (isDirty=false).
    expect(screen.getByRole('button', { name: '철회' })).toBeEnabled();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
  });

  it('displaysRevokeGuidanceWhenAllNone — 설정 실재 + 전부 지시 없음 + 빈 목록 → U-32 (B-24 / BL-09)', async () => {
    mockConfigApi(() => jsonResponse({ captureParams: false } satisfies InstrumentConfigPayload));
    renderPanel();
    const paramsGroup = await screen.findByRole('group', { name: /JDBC 파라미터 캡처/ });
    // 사용자가 유일한 지시를 '지시 없음' 으로 되돌린다 → 전부 none + 빈 목록 (BL-09 상태).
    fireEvent.click(within(paramsGroup).getByRole('radio', { name: '지시 없음' }));
    // U-32 유도 + C-05 비활성 + C-06 활성 (UX §7.2 역방향 표).
    expect(
      await screen.findByText(/이 상태는 저장 대신 \[철회\] 로 만들어요/),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '철회' })).toBeEnabled();
  });

  it('acceptsAxisChangeAndEnablesSave — 빈 상태에서 축 하나 지시 → isDirty → [저장] 활성 (첫 설정 경로)', async () => {
    mockConfigApi(() => jsonResponse(NOT_FOUND_BODY, 404));
    renderPanel();
    const resultSetGroup = await screen.findByRole('group', { name: /JDBC 결과 캡처/ });
    fireEvent.click(within(resultSetGroup).getByRole('radio', { name: '줄이기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '저장' })).toBeEnabled());
  });

  it('displaysTokenErrorStateOn401 — 상태 C: 기존 ErrorState 단독, 폼 미표시 (T-10 재사용)', async () => {
    mockConfigApi(() => jsonResponse({ error: 'unauthorized' }, 401));
    renderPanel();
    // 기존 401 분기 문구 재사용 — 신규 문구 0. 전역 retry:1 로 1회 재시도(backoff ~1초) 후
    // 오류 확정되므로 대기 시간을 늘린다 (재시도 유지 자체가 의도 — 설계 §2.4 "401·5xx 는 전역 재시도 1회").
    expect(await screen.findByText('토큰이 필요해요', undefined, { timeout: 4_000 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '설정으로 이동' })).toBeInTheDocument();
    // 폼 미표시.
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument();
  });

  it('keepsSingleRequestOn404 — 404 는 성공 값(null)이라 재시도 대상 자체가 아니다 (G-12 구조 검증)', async () => {
    const spy = mockConfigApi(() => jsonResponse(NOT_FOUND_BODY, 404));
    renderPanel();
    await screen.findByText(/아직 저장된 지시가 없어요 \(정상\)/);
    const configCalls = spy.mock.calls.filter(([input]) => {
      const url =
        typeof input === 'string' ? input : input instanceof URL ? input.toString() : (input as Request).url;
      return url.includes('/instrument-config');
    });
    // 전역 retry: 1 이 살아 있는 클라이언트에서도 정확히 1회 — 재시도가 구조적으로 없다.
    expect(configCalls).toHaveLength(1);
  });

  it('displaysVersionLabelWithLastSeenPhrase — 머리 버전 라벨 = "agent 버전 (마지막 확인 시점)" (T-23 통일)', async () => {
    mockConfigApi(() => jsonResponse(NOT_FOUND_BODY, 404));
    renderPanel();
    expect(await screen.findByText(/agent 버전 \(마지막 확인 시점\)/)).toBeInTheDocument();
  });
});

// ── ExcludeListEditor — 서버 규칙 선반영 검증 경계 (B-14~B-17 / G-18) ──────────
//
// R21/AC-02-9 verbatim: "입력 검증은 서버 규칙 그대로 선반영 — 서비스명 200자·목록 100개·
// 항목 512자·항목 안 콤마 금지 (G-18). 서버 400 문구와 화면 안내 문구가 어긋나지 않는다."
// 정방향 동사(accepts*/displays*/keeps*/trims*) — 반대 방향 lock-in 동사 0건.

/** gateExcludes 규칙(100개·512자·카운터 줄)으로 편집기를 단독 렌더하는 하네스. */
function EditorHarness({ initial }: { initial: string[] }): ReactNode {
  const [items, setItems] = useState<string[]>(initial);
  return (
    <ExcludeListEditor
      items={items}
      onItemsChange={setItems}
      disabled={false}
      removeDisabled={false}
      placeholder="클래스 전체 이름 입력"
      inputLabel="개별 제외 클래스 추가"
      maxItems={100}
      maxItemLength={512}
      showCounterLine
    />
  );
}

function renderEditor(initial: string[] = []): void {
  render(<EditorHarness initial={initial} />);
}

function addItem(value: string): void {
  fireEvent.change(screen.getByRole('textbox'), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: '추가' }));
}

describe('ExcludeListEditor — 검증 경계 (B-14~B-17)', () => {
  it('accepts512CharItem — 512자 항목은 통과 (B-14 경계 안쪽)', () => {
    renderEditor();
    const item = 'a'.repeat(512);
    addItem(item);
    expect(screen.getByTitle(item)).toBeInTheDocument(); // 목록에 실림 (title = 전체 이름 복원)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('displaysLengthErrorFor513CharItem — 513자는 인라인 오류 + 목록 불변 (B-14)', () => {
    renderEditor();
    addItem('a'.repeat(513));
    expect(screen.getByRole('alert')).toHaveTextContent('항목은 512자 이내여야 해요.');
    expect(screen.getByText('0 / 100개 · 항목당 512자 이내 · 콤마는 쓸 수 없어요')).toBeInTheDocument();
  });

  it('acceptsUpToLimitThenDisablesInput — 99→100번째 추가 통과, 100 도달 시 입력·버튼 비활성 (B-15 / C-03)', () => {
    renderEditor(Array.from({ length: 99 }, (_, i) => `com.acme.C${i}`));
    addItem('com.acme.C99'); // 100번째 — 통과
    expect(screen.getByText('100 / 100개 · 항목당 512자 이내 · 콤마는 쓸 수 없어요')).toBeInTheDocument();
    // 101번째는 추가 표면 자체가 잠긴다 (비활성 전환) + 안내.
    expect(screen.getByRole('textbox')).toBeDisabled();
    expect(screen.getByRole('button', { name: '추가' })).toBeDisabled();
    expect(screen.getByText('최대 100개까지예요.')).toBeInTheDocument();
  });

  it('displaysCommaErrorWithServerWording — "a,b" → 서버 400 과 동일 문구 (B-16 / V)', () => {
    renderEditor();
    addItem('a,b');
    // V — 서버 400 문구와 byte 동일: "항목에는 콤마를 쓸 수 없습니다"
    expect(screen.getByRole('alert')).toHaveTextContent('항목에는 콤마를 쓸 수 없습니다');
  });

  it('trimsWhitespaceBeforeSave — " x " 는 trim 후 저장 (B-17)', () => {
    renderEditor();
    addItem('  com.acme.X  ');
    expect(screen.getByTitle('com.acme.X')).toBeInTheDocument();
  });

  it('keepsListOnDuplicateAdd — 기존 항목 재추가 → U-38 + 미추가 (B-17)', () => {
    renderEditor(['com.acme.Dup']);
    addItem('com.acme.Dup');
    expect(screen.getByRole('alert')).toHaveTextContent('이미 목록에 있어요.');
    expect(screen.getAllByTitle('com.acme.Dup')).toHaveLength(1); // 목록 불변
  });
});
