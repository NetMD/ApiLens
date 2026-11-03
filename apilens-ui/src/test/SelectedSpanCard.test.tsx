// [R26/AC-R26-44] (U1) SelectedSpanCard — span 상세 속성 묶음 **표시 순서 고정**.
//
// AC 원문: "span 상세 속성 묶음 **표시 순서 고정 시험 1건** 신설 · **코드 변경 0**"
//
// 왜 이 시험이 필요한가: 묶음 차례(HTTP → DB → Code → Other)와 묶음 안 차례는
// SelectedSpanCard.tsx 의 groupAttributes() 가 정하는데, 그 규약을 잡아 주는 시험이 한 건도
// 없었다. 정렬 줄을 지우거나 배열 차례를 바꿔도 모든 시험이 초록이었다 — 지금까지는
// **사람 눈만이** 이 규약의 유일한 관문이었다.
//
// 무엇을 눌러 보나: 일부러 **뒤섞은 차례로** 속성을 넣고, 화면에 그려진 차례가 규약대로
// 다시 정렬됐는지 본다. 넣은 차례 그대로 나오면 빨개진다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   displaysAttributeGroupsAndKeysInTheFixedOrder — 묶음 차례 + 묶음 안 차례를 한 번에
// 반대 방향 lock-in 동사(hides*/rejects*/skips*) 0건.
//
// ★시험은 **1건**이다 — 설계 §7-2 가 화면 신설 시험 수를 3(U1·U2·U3)으로 미리 적었고
//   그 수가 판별식의 분모다. 한 건 안에서 두 축(묶음 차례 · 키 차례)을 함께 단언한다.
// ★코드 변경 0 — 이 라운드는 SelectedSpanCard.tsx 를 한 글자도 안 고쳤다. 지금 동작을
//   그대로 고정하는 시험만 더한다.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { SelectedSpanCard } from '../components/SelectedSpanCard';
import { shortenOperation } from '../lib/format';
import type { SpanDetail } from '../types/api';

function makeSpan(attributes: Record<string, unknown>): SpanDetail {
  return {
    spanId: 'a1b2c3d4e5f60718',
    parentSpanId: null,
    serviceName: 'my-api',
    operationName: 'com.example.OrderController#create',
    spanKind: 'SERVER',
    startTime: 1_716_386_700_000,
    endTime: 1_716_386_700_120,
    status: 'OK',
    attributes,
  };
}

function renderCard(span: SpanDetail): void {
  // PayloadView 가 useQuery 를 쓰므로 Provider 가 필요하다. 이 시험은 payload 를 안 보므로
  // 조회는 실패해도 된다 — retry 를 꺼서 실패가 시험 시간을 늘리지 않게만 한다.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  const Wrapper = (): ReactNode => (
    <QueryClientProvider client={queryClient}>
      <SelectedSpanCard traceId="0af7651916cd43dd8448eb211c80319c" span={span} />
    </QueryClientProvider>
  );
  render(<Wrapper />);
}

describe('SelectedSpanCard — 속성 묶음 표시 순서 고정 [R26/AC-R26-44]', () => {
  it('displaysAttributeGroupsAndKeysInTheFixedOrder — 뒤섞어 넣어도 HTTP → DB → Code → Other 차례로, 묶음 안은 정의 차례(Other 는 사전순)로 그려진다', () => {
    renderCard(
      makeSpan({
        // 전부 규약의 정반대에 가까운 차례로 넣는다.
        // Other — 사전순의 정반대
        'zzz.custom': 'z',
        'mmm.custom': 'm',
        // Code — 정의 차례(namespace → function)의 정반대
        'code.function': 'create',
        'code.namespace': 'com.example.OrderService',
        // DB — 정의 차례(statement → parameters → rows_affected → connection)의 정반대
        'db.connection': 'jdbc:sqlite',
        'db.rows_affected': 1,
        'db.statement': 'insert into orders values (?)',
        // HTTP — 정의 차례(method → url → route → status_code)의 정반대
        'http.status_code': 201,
        'http.route': '/orders',
        'http.url': 'http://localhost:8765/orders',
        'http.method': 'POST',
        'aaa.custom': 'a',
      }),
    );

    const card = screen.getByLabelText('Selected span details');
    const labels = Array.from(card.querySelectorAll('h3')).map((h) => h.textContent ?? '');
    const keys = Array.from(card.querySelectorAll('dt')).map((d) => d.textContent ?? '');

    // 전제 단언 — 묶음 넷과 키 열둘이 실제로 다 그려져야 아래 차례 대조가 뜻을 가진다.
    // (빈 배열끼리 견주면 [] === [] 로 통과해 버린다.)
    expect(labels).toHaveLength(4);
    expect(keys).toHaveLength(12);

    // 축 ① 묶음 차례
    expect(labels).toEqual(['HTTP', 'DB', 'Code', 'Other']);

    // 축 ② 묶음 안 차례
    expect(keys).toEqual([
      // HTTP — 정의 차례로 되돌아온다
      'http.method',
      'http.url',
      'http.route',
      'http.status_code',
      // DB — 정의 차례 (안 넣은 db.parameters 는 건너뛴다)
      'db.statement',
      'db.rows_affected',
      'db.connection',
      // Code — 정의 차례
      'code.namespace',
      'code.function',
      // Other — 사전순
      'aaa.custom',
      'mmm.custom',
      'zzz.custom',
    ]);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// [Phase AB / R31] TC-R31-85~87 — 외부 호출(CLIENT) 카드(설계 부록 A2-5 · 한 행 = it 하나).
//   HTTP 묶음에 상대 주소 · 상대 포트 키(「Other」 로 안 떨어짐) · 카드 머리 마우스 올림 글 · 오류 상자 타입 줄.
//   사용자 명시 비협상 결정(UD-R31-6 「상세 패널에 호스트·경로·상태 코드」 · 「에러 시 stack trace 박스」).
// ──────────────────────────────────────────────────────────────────────────────
const CLIENT_OP = 'HTTP POST 192.0.2.39:1234/v1/chat/completions';

function makeClientSpan(
  attributes: Record<string, unknown>,
  status: SpanDetail['status'] = 'OK',
): SpanDetail {
  return {
    spanId: 'c0ffee00c0ffee00',
    parentSpanId: 'a1b2c3d4e5f60718',
    serviceName: 'my-api',
    operationName: CLIENT_OP,
    spanKind: 'CLIENT',
    startTime: 1_716_386_700_000,
    endTime: 1_716_386_700_500,
    status,
    attributes,
  };
}

/** 묶음 이름 → 그 묶음의 키 차례. */
function keysByGroup(card: HTMLElement): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  card.querySelectorAll('h3').forEach((h) => {
    const dl = h.nextElementSibling;
    out[h.textContent ?? ''] = dl
      ? Array.from(dl.querySelectorAll('dt')).map((d) => d.textContent ?? '')
      : [];
  });
  return out;
}

describe('SelectedSpanCard — 외부 호출(CLIENT) 카드 [R31]', () => {
  // 카드 안 PayloadView 가 payload 를 조회한다 — 이 묶음은 망에 안 나가도록 빈 응답으로 막는다
  //   (happy-dom 기본 주소 localhost:3000 으로 실제 요청이 나가는 것을 막음 · 위 R26 시험은 손대지 않는다).
  beforeEach(() => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      Promise.resolve(
        new Response(JSON.stringify({ payloads: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('TC-R31-85 CLIENT 카드 HTTP 묶음 키 차례 6 · 「Other」 에 상대 주소 · 포트 키 0 (AC-R31-32)', () => {
    renderCard(
      makeClientSpan({
        // 정의 차례의 정반대로 넣는다.
        'zzz.custom': 'z',
        'http.status_code': 200,
        'http.route': '/v1/chat/completions',
        'server.port': 1234,
        'server.address': '192.0.2.39',
        'http.url': 'http://192.0.2.39:1234/v1/chat/completions',
        'http.method': 'POST',
      }),
    );
    const groups = keysByGroup(screen.getByLabelText('Selected span details'));
    // 전제 단언 — HTTP 묶음이 실제로 그려졌다.
    expect(groups['HTTP']).toBeDefined();
    expect(groups['HTTP']).toEqual([
      'http.method',
      'http.url',
      'server.address',
      'server.port',
      'http.route',
      'http.status_code',
    ]);
    expect(groups['Other']).toEqual(['zzz.custom']);
    expect((groups['Other'] ?? []).filter((k) => k.startsWith('server.'))).toHaveLength(0);
  });

  it('TC-R31-86 카드 머리 글자 = shortenOperation(operationName) · title = 원문 (AC-R31-34 · 35)', () => {
    renderCard(makeClientSpan({ 'http.method': 'POST' }));
    const card = screen.getByLabelText('Selected span details');
    const head = card.querySelector('header > div');
    expect(head).not.toBeNull();
    // CLIENT 이름에는 '#' 가 없어 shortenOperation 결과 = 원문 — 호스트가 온전하다(AC-R31-26 조각 0).
    expect(head!.textContent).toBe(shortenOperation(CLIENT_OP));
    expect(head!.textContent).not.toContain('39:1234#');
    expect(head!.getAttribute('title')).toBe(CLIENT_OP);
    expect(head!.classList.contains('break-all')).toBe(true);
  });

  it('TC-R31-87 ERROR CLIENT 오류 상자 = 타입 줄 + 메시지 + 스택 · 타입 없는 4xx 는 「(no message)」 그대로 (AC-R31-62 카드)', () => {
    const stack =
      'java.net.SocketTimeoutException: Read timed out\n\tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:278)';
    const first = renderCardReturning(
      makeClientSpan(
        {
          'http.method': 'POST',
          'exception.type': 'SocketTimeoutException',
          'exception.message': 'Read timed out',
          'exception.stacktrace': stack,
        },
        'ERROR',
      ),
    );
    const box = first.container.querySelector('.border-l-4');
    expect(box).not.toBeNull();
    expect(box!.children).toHaveLength(3);
    expect(box!.children[0]!.textContent).toBe('SocketTimeoutException');
    expect(box!.children[1]!.textContent).toBe('Read timed out');
    expect(box!.querySelector('pre')!.textContent).toBe(stack);
    first.unmount();

    const second = renderCardReturning(
      makeClientSpan({ 'http.method': 'GET', 'http.status_code': 404 }, 'ERROR'),
    );
    const plainBox = second.container.querySelector('.border-l-4');
    expect(plainBox).not.toBeNull();
    expect(plainBox!.children).toHaveLength(2);
    expect(plainBox!.children[0]!.textContent).toBe('(no message)');
    expect(plainBox!.querySelector('pre')!.textContent).toBe('(stacktrace not captured)');
  });
});

/** 같은 시험 안에서 두 번 그리려고 render 결과를 돌려주는 변형(위 renderCard 는 그대로 둔다). */
function renderCardReturning(span: SpanDetail): ReturnType<typeof render> {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SelectedSpanCard traceId="0af7651916cd43dd8448eb211c80319c" span={span} />
    </QueryClientProvider>,
  );
}
