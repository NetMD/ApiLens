// [Phase R19] 계측 분석 화면 — 임계 UI 반응 · 절감/부작용 짝 · 확인 1단계 (설계 §8.4 B-25~B-29).
//
// 검증 의무 (정방향 동사 명시 — lock-in 회귀 가드. 본 라운드는 사용자 명시 비협상 결정 D-1~D-14 보유):
//   opensGuidanceWithoutWarningBelowWarnThreshold — B-25: 0.4999 → 경고 없음 · 적용 전 안내 열림
//   opensGuidanceWithoutWarningAtWarnThreshold    — B-26: 0.50 → 경고 없음 (`>` 비교 — 초과부터)
//   showsWeakWarningAboveWarnThreshold            — B-27: 0.5001 → 주의 · 확인 없이 안내 열림
//   keepsWeakWarningAtSevereThreshold             — B-28: 0.80 → 약한 경고 유지
//   showsSevereWarningAboveSevereThreshold        — B-29: 0.8001 → 경고 · 확인 전 안내 잠김
//   acceptsAcknowledgeThenOpensGuidance           — B-29: [영향을 확인했어요] 는 절대 비활성 아님 → 누르면 안내 열림
//   displaysSavingsAndImpactTogether              — 절감은 언제나 부작용과 짝으로 뜬다 (AC-05-1)
//   displaysSavingsBeforeAcknowledge              — 확인 전에도 절감을 숨기지 않는다 (C-11)
//   rendersCheckboxOnlyForSelectableRows          — 불가 행은 체크박스 DOM 자체가 없다 (C-24)
//   displaysUncertainBadgeAsSeparateWording       — 불확실을 "뺄 수 있어요" 로 반올림하지 않는다
//   displaysAllThreeAxesWithServerRanks           — 세 축 상시 표시 + 서버가 준 순위 병기
//   showsUnsupportedAgentNoticeForOldAgent        — agent 버전 미지원 알림
//   displaysProxyReasonWithRemoteGateAlternative  — [Phase R20] R20/AC-11-2·AC-12-1: 불가 사유 ⓐ + 대안 안내 ⓑ 2요소 문구 상시 노출
// 반대 방향 lock-in 동사(hides*/rejects*/denies*) 0건.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router';
import { InstrumentAnalysis } from '../components/instrument/InstrumentAnalysis';
import type {
  AnalysisResponse,
  ServiceInfo,
  SimulationResponse,
} from '../types/api';

const WINDOW = { fromMs: 1_785_340_800_000, toMs: 1_785_344_400_000, queriedAtMs: 1_785_344_400_123 };

function makeService(agentVersion: string | null = '0.4.0'): ServiceInfo {
  return {
    name: 'orders-api',
    registeredAt: 1_785_000_000_000,
    lastSeenAt: 1_785_344_000_000,
    source: 'auto',
    traceCount: 12_483,
    healthStatus: 'active',
    agentVersion,
  };
}

const ANALYSIS: AnalysisResponse = {
  window: WINDOW,
  summary: {
    totalSpans: 15_952,
    totalTraces: 6_104,
    avgSpansPerTrace: 2.613,
    singleSpanTraceRatio: 0.197,
  },
  totalClasses: 87,
  truncated: false,
  items: [
    {
      className: '',
      spanCount: 7_841,
      payloadCount: 15_602,
      payloadBytes: 8_804_234_752,
      spanRank: null,
      payloadCountRank: null,
      payloadBytesRank: null,
      rootRatio: 0.0,
      backgroundWorker: false,
      excludeStatus: 'NOT_EXCLUDABLE',
      excludeReasonCode: 'NO_CLASS_NAME',
      excludeTarget: null,
    },
    {
      className: 'com.acme.batch.OrderSyncJob',
      spanCount: 2_140,
      payloadCount: 1_070,
      payloadBytes: 220_200_960,
      spanRank: 2,
      payloadCountRank: 4,
      payloadBytesRank: 16,
      rootRatio: 1.0,
      backgroundWorker: true,
      excludeStatus: 'EXCLUDABLE',
      excludeReasonCode: null,
      excludeTarget: 'com.acme.batch.OrderSyncJob',
    },
    {
      className: 'com.acme.report.ReportMapper',
      spanCount: 402,
      payloadCount: 399,
      payloadBytes: 3_328_599_654,
      spanRank: 12,
      payloadCountRank: 11,
      payloadBytesRank: 3,
      rootRatio: 0.657,
      backgroundWorker: false,
      excludeStatus: 'NOT_EXCLUDABLE',
      excludeReasonCode: 'PROXY_INSTRUMENTED',
      excludeTarget: null,
    },
    {
      className: 'com.acme.dao.OrderDao',
      spanCount: 300,
      payloadCount: 120,
      payloadBytes: 1_048_576,
      spanRank: 20,
      payloadCountRank: 21,
      payloadBytesRank: 22,
      rootRatio: 0.1,
      backgroundWorker: false,
      excludeStatus: 'UNKNOWN',
      excludeReasonCode: 'UNVERIFIED_PATH',
      excludeTarget: null,
    },
  ],
};

function makeSimulation(singleSpanTraceRatio: number): SimulationResponse {
  return {
    window: WINDOW,
    savings: { spanDelta: 2_402, payloadCountDelta: 1_204, payloadBytesDelta: 232_816_640 },
    impact: {
      remainingSpans: 13_550,
      resultTraces: 6_248,
      avgSpansPerTrace: 2.169,
      singleSpanTraceRatio,
    },
    depthCapped: false,
  };
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 두 endpoint mock. simulation 의 단일 span trace 비율만 케이스별로 갈아 끼운다. */
function mockApi(orphanRatio: number): void {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/instrument/analysis')) {
      return Promise.resolve(jsonResponse(ANALYSIS));
    }
    if (url.includes('/v1/instrument/simulation')) {
      return Promise.resolve(jsonResponse(makeSimulation(orphanRatio)));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
}

/**
 * [Phase R25] 분석 응답만 갈아 끼우는 mock — 「고유 본문 합」 줄의 두 갈래(키 없음 / 0)를 재려고 둔다.
 * 위 mockApi 는 상수 ANALYSIS 고정이라 응답을 바꿔 끼울 수 없다. 기존 mockApi 는 **안 고친다**
 * (기존 13건이 그 함수를 쓰고 있어 손대면 이번 라운드 밖 시험이 흔들린다).
 */
function mockAnalysisOnly(analysis: AnalysisResponse): void {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url =
      typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
    if (url.includes('/v1/instrument/analysis')) {
      return Promise.resolve(jsonResponse(analysis));
    }
    return Promise.resolve(jsonResponse({ error: 'unexpected' }, 500));
  });
}

function renderScreen(agentVersion: string | null = '0.4.0'): void {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/services?analyze=orders-api']}>
        <InstrumentAnalysis service={makeService(agentVersion)} onBack={() => undefined} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** 분석 실행 → 대상 1건 선택 → 시뮬레이션 실행 까지의 공통 흐름. */
async function runToSimulation(): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
  const checkbox = await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택');
  fireEvent.click(checkbox);
  fireEvent.click(screen.getByRole('button', { name: '빼면 어떻게 되는지 보기' }));
  await screen.findByText('빼면 이렇게 돼요');
}

describe('계측 분석 화면 — 임계 반응 · 절감/부작용 짝 · 확인 1단계', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('opensGuidanceWithoutWarningBelowWarnThreshold — B-25: 0.4999 는 경고 없이 적용 전 안내가 열린다', async () => {
    mockApi(0.4999);
    renderScreen();
    await runToSimulation();

    expect(screen.getByText('적용 전에 알아 두세요')).toBeInTheDocument();
    expect(screen.queryByText(/^⚠? ?주의 — 빼고 나면/)).toBe(null);
    expect(screen.queryByText(/경고 — 빼고 나면/)).toBe(null);
  });

  it('opensGuidanceWithoutWarningAtWarnThreshold — B-26: 0.50 은 경고 없음 (초과부터 경고)', async () => {
    mockApi(0.5);
    renderScreen();
    await runToSimulation();

    expect(screen.getByText('적용 전에 알아 두세요')).toBeInTheDocument();
    expect(screen.queryByText(/주의 — 빼고 나면/)).toBe(null);
  });

  it('showsWeakWarningAboveWarnThreshold — B-27: 0.5001 은 주의 + 확인 없이 안내가 열린다', async () => {
    mockApi(0.5001);
    renderScreen();
    await runToSimulation();

    expect(screen.getByText(/주의 — 빼고 나면 span 이 하나뿐인 trace 가 약/)).toBeInTheDocument();
    expect(screen.getByText('적용 전에 알아 두세요')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '영향을 확인했어요' })).toBe(null);
  });

  it('keepsWeakWarningAtSevereThreshold — B-28: 0.80 은 약한 경고를 유지한다', async () => {
    mockApi(0.8);
    renderScreen();
    await runToSimulation();

    expect(screen.getByText(/주의 — 빼고 나면 span 이 하나뿐인 trace 가 약/)).toBeInTheDocument();
    expect(screen.queryByText(/경고 — 빼고 나면/)).toBe(null);
    expect(screen.getByText('적용 전에 알아 두세요')).toBeInTheDocument();
  });

  it('showsSevereWarningAboveSevereThreshold — B-29: 0.8001 은 경고 + 확인 전에는 안내가 안 열린다', async () => {
    mockApi(0.8001);
    renderScreen();
    await runToSimulation();

    expect(screen.getByText(/경고 — 빼고 나면 span 이 하나뿐인 trace 가 약/)).toBeInTheDocument();
    expect(screen.queryByText('적용 전에 알아 두세요')).toBe(null);
  });

  it('acceptsAcknowledgeThenOpensGuidance — B-29: 확인 버튼은 절대 비활성이 아니고, 누르면 안내가 열린다', async () => {
    mockApi(0.8001);
    renderScreen();
    await runToSimulation();

    const confirm = screen.getByRole('button', { name: '영향을 확인했어요' });
    // ⛔ 막지 않는다 — 경고가 아무리 세도 disabled 가 붙지 않는다 (C-10 비협상).
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);
    expect(await screen.findByText('적용 전에 알아 두세요')).toBeInTheDocument();
  });

  it('displaysSavingsAndImpactTogether — 절감은 언제나 부작용 지표와 짝으로 뜬다', async () => {
    mockApi(0.83);
    renderScreen();
    await runToSimulation();

    // 부작용 (지금 → 빼고 나면)
    expect(screen.getByText('남는 span 수')).toBeInTheDocument();
    expect(screen.getByText('결과 trace 수')).toBeInTheDocument();
    expect(screen.getByText('trace 당 평균 span')).toBeInTheDocument();
    expect(screen.getByText('span 이 하나뿐인 trace 비율')).toBeInTheDocument();
    // 절감 + 한정 문구
    expect(screen.getByText('예상 절감 (저장 기준)')).toBeInTheDocument();
    expect(screen.getByText(/payload 약 .* 줄어요 · span 약 .*건 줄어요/)).toBeInTheDocument();
    expect(
      screen.getByText('이 구간의 자료를 그대로 다시 흘려보낼 때의 값이에요.'),
    ).toBeInTheDocument();
    // 비율은 화면에서만 백분율로 바뀐다 (기준값 20% → 83%)
    expect(screen.getByText('83%')).toBeInTheDocument();
  });

  it('displaysSavingsBeforeAcknowledge — 확인 전에도 절감을 숨기지 않는다', async () => {
    mockApi(0.9);
    renderScreen();
    await runToSimulation();

    expect(screen.getByRole('button', { name: '영향을 확인했어요' })).toBeInTheDocument();
    expect(screen.getByText('예상 절감 (저장 기준)')).toBeInTheDocument();
    expect(screen.getByText('빼면 이렇게 돼요')).toBeInTheDocument();
  });

  it('rendersCheckboxOnlyForSelectableRows — 불가 행은 체크박스 DOM 자체가 없다', async () => {
    mockApi(0.3);
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택');

    // 불확실(UNKNOWN) 행은 고를 수 있다.
    expect(screen.getByLabelText('com.acme.dao.OrderDao 선택')).toBeInTheDocument();
    // 불가(NOT_EXCLUDABLE) 행 · 고정 합계 행은 체크박스가 아예 없다.
    expect(screen.queryByLabelText('com.acme.report.ReportMapper 선택')).toBe(null);
    expect(screen.getAllByRole('checkbox')).toHaveLength(2);
  });

  it('displaysUncertainBadgeAsSeparateWording — 불확실을 "뺄 수 있어요" 로 반올림하지 않는다', async () => {
    mockApi(0.3);
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택');

    expect(screen.getByText('확인 안 됨')).toBeInTheDocument();
    expect(screen.getAllByText('뺄 수 없어요')).toHaveLength(2); // 고정 합계 행 + mapper 행
    expect(screen.getAllByText('뺄 수 있어요')).toHaveLength(1);
    // 불확실 사유는 툴팁이 아니라 행 안에 상시 노출된다.
    expect(
      screen.getByText('이 계층이 실제로 빠지는지 확인하지 못했어요. 적용 뒤 직접 확인해 주세요'),
    ).toBeInTheDocument();
  });

  it('displaysProxyReasonWithRemoteGateAlternative — mapper 불가 사유는 불가 ⓐ + 대안 ⓑ 2요소 문구로 행 안에 상시 노출된다', async () => {
    mockApi(0.3);
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택');

    // [Phase R20] R20/AC-11-2 — Plan AC verbatim: "최종 문구는 ⓐ 불가 사유 유지 + ⓑ 대안 안내, 2요소를 각각 충족해야
    // 한다. R19 T-31 은 ⓐ만 담고 ⓑ가 빠졌다 — 같은 갭 재발 시 FAIL" (비협상 — Q-U9).
    // 문구는 설계 §1-⑧ 확정값 바이트 그대로 단언한다 (가시 텍스트 — getByText 는 sr-only 전용이 아님을 함께 보증).
    expect(
      screen.getByText(
        '이 계층은 계측이 걸리는 이름이 화면에 보이는 이름과 달라서, 이 이름으로는 빠지지 않아요. 대신 원격 계측 설정으로는 이 이름 그대로 뺄 수 있어요 — 방법은 agent 옵션 문서를 보세요',
      ),
    ).toBeInTheDocument();
    // [Phase R20] R20/AC-12-2 — 판별 로직·배지 diff 0: mapper 행은 여전히 NOT_EXCLUDABLE (체크박스 DOM 부재 유지).
    expect(screen.queryByLabelText('com.acme.report.ReportMapper 선택')).toBe(null);
  });

  it('displaysAllThreeAxesWithServerRanks — 세 축을 항상 함께 보여 주고 순위는 서버 값을 그대로 쓴다', async () => {
    mockApi(0.3);
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택');

    // 어긋남이 한 줄에서 읽혀야 한다 — span #2 인데 바이트는 #16.
    expect(screen.getByText('#2')).toBeInTheDocument();
    expect(screen.getByText('#4')).toBeInTheDocument();
    expect(screen.getByText('#16')).toBeInTheDocument();
    // 세 축 1위가 서로 다른지 알리는 안내는 조건부다 (이 표본은 1위가 목록에 없어 미표시).
    expect(screen.getByText('절감 예측과 "실제로 뺄 수 있는지" 는 서로 다른 이야기예요')).toBeInTheDocument();
    // 정렬 축을 바꿔도 세 값과 순위는 그대로 남는다.
    fireEvent.click(screen.getByRole('button', { name: 'payload 크기' }));
    expect(screen.getByText('#2')).toBeInTheDocument();
    expect(screen.getByText('#16')).toBeInTheDocument();
  });

  it('showsUnsupportedAgentNoticeForOldAgent — 옵션을 모르는 agent 면 알림을 보여 준다', async () => {
    mockApi(0.3);
    renderScreen('0.3.9');
    await waitFor(() => {
      expect(
        screen.getByText(/이 서비스의 agent 는 계측 제외 옵션을 아직 몰라요/),
      ).toBeInTheDocument();
    });
    // 확인 안 됨 알림과 동시에 뜨지 않는다.
    expect(screen.queryByText(/agent 버전을 확인하지 못했어요/)).toBe(null);
  });

  it('showsUnknownAgentNoticeWhenVersionMissing — 값이 없으면 "확인 안 됨" 을 보여 준다 (미달 단정 금지)', async () => {
    mockApi(0.3);
    renderScreen(null);
    await waitFor(() => {
      expect(
        screen.getByText(/이 서비스의 agent 버전을 확인하지 못했어요/),
      ).toBeInTheDocument();
    });
    expect(screen.queryByText(/계측 제외 옵션을 아직 몰라요/)).toBe(null);
  });

  // [Phase R25] TS-R25-34 (AC-25-05-3/AC-25-05-4 · C-R25-03/C-R25-04) — 「고유 본문 합」 한 줄.
  // Plan AC verbatim: "AC-25-05-4 화면은 **「값이 없다(모름)」와 「값이 0이다」를 다르게 다룬다.**
  //   모름이면 안 그리고, 0이면 0으로 그린다. 둘을 같게 다루면 조회가 실패한 것과 정말 0인 것을 못 가른다."
  //   (라벨 「고유 본문 합」은 사용자 명시 확정 — 못 바꿈)
  // 한 시험 안에서 두 갈래를 잰다 — 같은 한 줄의 반대편 갈래라 따로 떼면 「무엇과 무엇이 달라야
  // 하는지」가 시험 이름에서 사라진다. 설계 §9.4 의 신설 목록 1행(TS-R25-34)과 1:1 이다.
  it('showsUniquePayloadBytesOnlyWhenTheServerSendsIt — TS-R25-34: 값이 없으면 안 그리고 0 이면 0 B 로 그린다', async () => {
    // ⓐ 서버가 그 키를 안 실은 응답(= 모름). ANALYSIS 픽스처에는 uniquePayloadBytes 키가 없다.
    //    선택 필드라 컴파일이 통과하고, 그 통과 자체가 「값 없음이면 안 그린다」의 첫 증거다.
    mockAnalysisOnly(ANALYSIS);
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    await screen.findByLabelText('com.acme.batch.OrderSyncJob 선택'); // 결과가 다 그려진 뒤에 잰다
    expect(screen.queryByText(/고유 본문 합/)).toBe(null);
    // 자리표(「—」 등)를 새로 만들지 않았다는 것도 함께 본다 (T-R25-03).
    expect(screen.queryByText('고유 본문 합 —')).toBe(null);

    cleanup();
    vi.restoreAllMocks();

    // ⓑ 값이 0인 응답. 0 은 「모름」이 아니다 — `0 B` 로 그린다.
    mockAnalysisOnly({ ...ANALYSIS, summary: { ...ANALYSIS.summary, uniquePayloadBytes: 0 } });
    renderScreen();
    fireEvent.click(screen.getByRole('button', { name: '분석 실행' }));
    expect(await screen.findByText('고유 본문 합 0 B')).toBeInTheDocument();
  });
});
