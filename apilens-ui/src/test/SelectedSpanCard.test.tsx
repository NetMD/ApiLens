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
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { SelectedSpanCard } from '../components/SelectedSpanCard';
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
