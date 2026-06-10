// Phase R12 (FR-B2/B3) — MaskingRulesSection 단위 테스트 (토글 낙관 모델 + 프리뷰 동봉).
//
// AC-B3-1 verbatim: "POST /v1/masking-rules/preview — 샘플 페이로드 + (저장 전 토글 상태가 반영된)
// 룰 세트 → 마스킹 결과 반환. 단위 테스트 (V-03)." (비협상 — 화면 룰 세트 동봉)
// AC-B4-3 verbatim: "default 룰 행에 삭제 버튼 비노출 (1차 방어 — UI) + API 4xx (2차 방어 — US-B2)."
//
// 검증 의무 (정방향 동사 명시 — EXT-003 lock-in 회귀 가드):
//   sendsScreenRuleStatesInPreviewRequest — 프리뷰 요청 본문에 저장 전 화면 토글 상태 동봉 (비협상)
//   rollsBackToggleOnMutationFailure — PATCH 실패 시 스위치 롤백 + 에러 토스트 (UX §5.1)
//   showsDeleteButtonOnlyOnCustomRuleRows — C-05 1차 방어 (default 행 삭제 버튼 DOM 부재)
//   showsDirtyNoticeWhileToggleUnconfirmed — T-23 dirty 구간 노출 (화면 ≠ 서버 확인 상태)
// 회귀 가드 (반대 방향 lock-in 차단): rejectsToggle / hidesPreview 같은 반대 방향 동사 0건
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MaskingRulesSection } from '../components/settings/MaskingRulesSection';
import { ToastProvider } from '../components/Toast';
import type { MaskingRule, PreviewRuleState } from '../types/api';

/** V1 시드 동형 픽스처 — default 1 + custom 1 (ruleId 는 설계 §5.3 예시 동형). */
const FIXTURE_RULES: MaskingRule[] = [
  {
    ruleId: 1,
    name: '주민번호',
    ruleType: 'regex',
    pattern: '\\d{6}-?\\d{7}',
    maskStrategy: 'partial',
    enabled: true,
    isDefault: true,
  },
  {
    ruleId: 5,
    name: 'my-api-key',
    ruleType: 'field_name',
    pattern: 'x-api-key',
    maskStrategy: 'full',
    enabled: true,
    isDefault: false,
  },
];

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

interface FetchLog {
  previewBodies: Array<{ sample: string | null; ruleStates: PreviewRuleState[] }>;
}

/**
 * URL/method 기반 fetch mock.
 * patchBehavior: 'hang' = 미해소 (저장 전 상태 검증용) / 'fail' = 500 (롤백 검증용) / 'ok' = 200.
 */
function mockApi(patchBehavior: 'hang' | 'fail' | 'ok'): FetchLog {
  const log: FetchLog = { previewBodies: [] };
  vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    const method = init?.method ?? 'GET';

    if (url.includes('/v1/masking-rules/preview')) {
      log.previewBodies.push(
        JSON.parse(String(init?.body)) as { sample: string | null; ruleStates: PreviewRuleState[] },
      );
      return Promise.resolve(
        jsonResponse({ sample: '{"ssn":"880101-1234567"}', masked: '{"ssn":"880101-*******"}', contentType: 'application/json' }),
      );
    }
    if (method === 'PATCH' && url.includes('/v1/masking-rules/')) {
      if (patchBehavior === 'hang') {
        return new Promise<Response>(() => undefined); // 의도적 미해소 — 서버 확인 전 상태 고정
      }
      if (patchBehavior === 'fail') {
        return Promise.resolve(jsonResponse({ error: 'boom' }, 500));
      }
      const body = JSON.parse(String(init?.body)) as { enabled: boolean };
      const target = FIXTURE_RULES.find((r) => url.includes(`/v1/masking-rules/${r.ruleId}`));
      return Promise.resolve(jsonResponse({ ...(target ?? FIXTURE_RULES[0]), enabled: body.enabled }));
    }
    if (method === 'GET' && url.includes('/v1/masking-rules')) {
      return Promise.resolve(jsonResponse({ rules: FIXTURE_RULES }));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected call' }, 500));
  });
  return log;
}

function renderSection(): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <MaskingRulesSection />
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe('MaskingRulesSection — [R12] 토글 낙관 모델 + 프리뷰 동봉', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sendsScreenRuleStatesInPreviewRequest — 프리뷰 요청 본문에 저장 전 화면 토글 상태 동봉 (AC-B3-1 비협상)', async () => {
    // PATCH 를 의도적으로 미해소 — 서버 persisted 상태가 바뀌기 전의 "화면 상태" 가 동봉됨을 증명.
    const log = mockApi('hang');
    renderSection();

    // 진입 즉시 1회 프리뷰 (기본 샘플 + 현재 룰 세트 — enabled 전부 true)
    await waitFor(() => expect(log.previewBodies.length).toBeGreaterThanOrEqual(1));
    expect(log.previewBodies[0]?.sample).toBe(null); // AC-B3-2 — null = 서버 내장 기본 샘플
    expect(log.previewBodies[0]?.ruleStates).toContainEqual({ ruleId: 1, enabled: true });

    // 토글 클릭 → 낙관 반전 → 디바운스(200ms) 후 프리뷰 재요청 — 화면 상태(false) 동봉
    fireEvent.click(screen.getByRole('switch', { name: '주민번호 활성화' }));
    await waitFor(() => {
      const last = log.previewBodies[log.previewBodies.length - 1];
      expect(last?.ruleStates).toContainEqual({ ruleId: 1, enabled: false });
    });
    // 전체 스냅샷 동봉 (부분 diff 아님 — 설계 §5.4): 미토글 룰도 포함
    const last = log.previewBodies[log.previewBodies.length - 1];
    expect(last?.ruleStates).toContainEqual({ ruleId: 5, enabled: true });
  });

  it('rollsBackToggleOnMutationFailure — PATCH 실패 시 스위치 롤백 + 에러 토스트 (UX §5.1 실패 분기)', async () => {
    mockApi('fail');
    renderSection();

    const ruleSwitch = await screen.findByRole('switch', { name: '주민번호 활성화' });
    expect(ruleSwitch).toHaveAttribute('aria-checked', 'true');

    fireEvent.click(ruleSwitch);
    // 낙관 반전 즉답 (mutation 해소 전)
    expect(ruleSwitch).toHaveAttribute('aria-checked', 'false');

    // 실패 → 로컬 상태 롤백 (서버 확인 상태 true 로 복귀) + 발의 #1 토스트
    await waitFor(() => expect(ruleSwitch).toHaveAttribute('aria-checked', 'true'));
    expect(screen.getByText('변경 실패 — 잠시 후 다시 시도해 주세요')).toBeInTheDocument();
  });

  it('showsDeleteButtonOnlyOnCustomRuleRows — C-05 1차 방어: default 행 삭제 버튼 DOM 부재 (AC-B4-3 비협상)', async () => {
    mockApi('ok');
    renderSection();

    // custom 행 (my-api-key) 에만 삭제 버튼 렌더
    expect(await screen.findByRole('button', { name: 'my-api-key 삭제' })).toBeInTheDocument();
    // default 행 (주민번호) 은 disabled 가 아닌 렌더 자체 제거 — queryBy 로 DOM 부재 단언
    expect(screen.queryByRole('button', { name: '주민번호 삭제' })).toBe(null);
    // 단 default 룰도 토글은 노출 (C-03 — 비활성만 가능 = 토글 허용)
    expect(screen.getByRole('switch', { name: '주민번호 활성화' })).toBeInTheDocument();
  });

  it('showsDirtyNoticeWhileToggleUnconfirmed — T-23: 화면 ≠ 서버 확인 상태 구간에만 안내 노출', async () => {
    mockApi('hang'); // 서버 확인이 오지 않는 동안 = dirty 구간 지속
    renderSection();

    const ruleSwitch = await screen.findByRole('switch', { name: '주민번호 활성화' });
    // 토글 전 — dirty 아님 → T-23 비노출
    expect(screen.queryByText('저장 전 변경 사항이 프리뷰에 반영되고 있어요.')).toBe(null);

    fireEvent.click(ruleSwitch);
    // 토글 후 서버 미확인 구간 — T-23 노출
    expect(
      await screen.findByText('저장 전 변경 사항이 프리뷰에 반영되고 있어요.'),
    ).toBeInTheDocument();
  });
});
