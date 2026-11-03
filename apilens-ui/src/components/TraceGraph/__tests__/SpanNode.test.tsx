// [Phase AB / R31] TC-R31-73~81 — SpanNode 렌더 시험(설계 부록 A2-5 · 한 행 = it 하나 · it.each 안 씀 · happy-dom 기본 환경).
//
// 외부 호출(CLIENT) 노드의 위 라벨 · 아래 줄 · 마우스 올림 글 · 색 · 반지름 · 오류 테를 고정한다.
// ReactFlowProvider 로 감싸 Handle 이 store 를 찾게 한다(노드 번호 문맥이 없어 생기는 경고는 개발 모드에서만 찍힌다).
//
// R-1 inline 대체(PM 호출 계약 10 · 비협상 GA-R31-12 · 출처 UD-R31-6) lock-in:
//   TC-R31-73 · 75 · 76 = 기획 [보충] AC-R31-25 · AC-R31-27 · [보충] AC-R31-28 의 글자 쪽.
//   원문 인용 = SpanNode.tsx secondaryLabel 의 CLIENT 갈래 주석.
// ★이 파일은 폭(px)을 재지 않는다 — happy-dom 은 글자 폭을 계산하지 않는다. 폭 · 겹침은 실브라우저 스모크(VC-R31-6 · AC-R31-28~31) 몫.
import { describe, expect, it } from 'vitest';
import { render } from '@testing-library/react';
import { ReactFlowProvider } from '@xyflow/react';
import type { NodeProps } from '@xyflow/react';
import type { SpanDetail, TraceStatus } from '../../../types/api';
import { SERVER_ADDRESS_KEY, SERVER_PORT_KEY } from '../../../lib/spanLabels';
import { SpanNode } from '../SpanNode';
import type { SpanFlowNode } from '../types';

const CLIENT_NAME = 'HTTP POST 192.0.2.39:1234/v1/chat/completions';

function clientSpan(overrides: Partial<SpanDetail> = {}): SpanDetail {
  return {
    spanId: 'c1',
    parentSpanId: 's1',
    serviceName: 'my-api',
    operationName: CLIENT_NAME,
    spanKind: 'CLIENT',
    startTime: 0,
    endTime: 500,
    status: 'OK',
    attributes: {
      'http.method': 'POST',
      'http.url': 'http://192.0.2.39:1234/v1/chat/completions',
      [SERVER_ADDRESS_KEY]: '192.0.2.39',
      [SERVER_PORT_KEY]: 1234,
      'http.status_code': 200,
    },
    ...overrides,
  };
}

function plainSpan(
  spanKind: SpanDetail['spanKind'],
  operationName: string,
  attributes: Record<string, unknown>,
  status: TraceStatus = 'OK',
): SpanDetail {
  return {
    spanId: `${spanKind}-1`,
    parentSpanId: null,
    serviceName: 'my-api',
    operationName,
    spanKind,
    startTime: 0,
    endTime: 500,
    status,
    attributes,
  };
}

function renderNode(span: SpanDetail): HTMLElement {
  // 시험은 tsc 대상 밖이지만 NodeProps 꼴을 맞춰 둔다 — SpanNode 가 읽는 것은 data · selected 뿐.
  const props = {
    id: span.spanId,
    type: 'span',
    data: { span },
    selected: false,
    dragging: false,
    zIndex: 0,
    isConnectable: false,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    selectable: true,
    deletable: false,
    draggable: false,
  } as unknown as NodeProps<SpanFlowNode>;
  const { container } = render(
    <ReactFlowProvider>
      <SpanNode {...props} />
    </ReactFlowProvider>,
  );
  return container;
}

/** 노드 위 라벨(글자 크기 10) 줄 글자 — 선택 알약은 selected=false 라 없다. */
function upperLines(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('text[font-size="10"]')).map((t) => t.textContent ?? '');
}

/** 노드 아래 줄(글자 크기 9) 요소들. */
function lowerTexts(container: HTMLElement): Element[] {
  return Array.from(container.querySelectorAll('text[font-size="9"]'));
}

/** 본체 원(fill 이 있는 원). */
function bodyCircle(container: HTMLElement): Element {
  const circles = Array.from(container.querySelectorAll('circle')).filter(
    (c) => c.getAttribute('fill') !== 'none',
  );
  expect(circles).toHaveLength(1);
  return circles[0]!;
}

describe('SpanNode — 외부 호출(CLIENT) 노드 [R31]', () => {
  it('TC-R31-73 CLIENT 아래 줄 = <text> 하나 · <tspan> 0 · 글자 「POST 192.0.2.39:1234/v1/chat/c…」 (AC-R31-25 · 29)', () => {
    const container = renderNode(clientSpan());
    const lower = lowerTexts(container);
    // 전제 단언 — 아래 줄 <text> 가 실제로 있고 글자가 비지 않아야 아래 「<tspan> 0」 이 뜻을 가진다.
    expect(lower).toHaveLength(1);
    expect((lower[0]!.textContent ?? '').length).toBeGreaterThan(0);
    expect(container.querySelectorAll('tspan')).toHaveLength(0);
    // AC-R31-25 예시(`POST 192.0.2.39:1234/v1/chat/completions`)는 줄이기 전 글자다 — 노드는 31 글자로 끝 자른 꼴(DV-U-R31-1).
    expect(lower[0]!.textContent).toBe('POST 192.0.2.39:1234/v1/chat/c…');
  });

  it('TC-R31-74 CLIENT 위 라벨 = 두 줄(「HTTP POST」 / 호스트로 시작) · 줄마다 ≤ 28 (AC-R31-26 · 31)', () => {
    const lines = upperLines(renderNode(clientSpan()));
    expect(lines).toHaveLength(2);
    expect(lines[0]).toBe('HTTP POST');
    expect(lines[1]!.startsWith('192.0.2.39:1234')).toBe(true);
    for (const line of lines) {
      expect(line.length).toBeLessThanOrEqual(28);
      expect(line).not.toContain('39:1234#');
    }
  });

  it('TC-R31-75 바깥 틀 title = operation 이름 원문 (줄이지 않은 전체 · AC-R31-27)', () => {
    const container = renderNode(clientSpan());
    const titled = container.querySelector('div[title]');
    expect(titled).not.toBeNull();
    expect(titled!.getAttribute('title')).toBe(CLIENT_NAME);
  });

  it('TC-R31-76 BV-R31-01 긴 경로 CLIENT → 아래 줄 ≤ 31 글자 · 끝 「…」 (AC-R31-28)', () => {
    const path = '/' + 'a'.repeat(199);
    const container = renderNode(
      clientSpan({
        operationName: `HTTP POST 192.0.2.39:1234${path}`,
        attributes: {
          'http.method': 'POST',
          'http.url': `http://192.0.2.39:1234${path}`,
          [SERVER_ADDRESS_KEY]: '192.0.2.39',
          [SERVER_PORT_KEY]: 1234,
        },
      }),
    );
    const lower = lowerTexts(container);
    expect(lower).toHaveLength(1);
    const text = lower[0]!.textContent ?? '';
    expect(text.length).toBeGreaterThan(0);
    expect(text.length).toBeLessThanOrEqual(31);
    expect(text.endsWith('…')).toBe(true);
    // 위 라벨도 줄마다 28 이하 · 두 줄 이하.
    const lines = upperLines(container);
    expect(lines.length).toBeLessThanOrEqual(2);
    for (const line of lines) expect(line.length).toBeLessThanOrEqual(28);
  });

  it('TC-R31-77 CLIENT 원 채움 = 주황 #EF9F27 (AC-R31-42)', () => {
    expect(bodyCircle(renderNode(clientSpan())).getAttribute('fill')).toBe('#EF9F27');
  });

  it('TC-R31-78 같은 길이 · 같은 상태의 CLIENT · INTERNAL 원 반지름이 같다 (길이 강조 0 · AC-R31-61)', () => {
    const clientR = bodyCircle(renderNode(clientSpan())).getAttribute('r');
    const internalR = bodyCircle(
      renderNode(plainSpan('INTERNAL', 'com.example.OrderService#place', {})),
    ).getAttribute('r');
    expect(clientR).not.toBeNull();
    expect(clientR).toBe(internalR);
  });

  it('TC-R31-79 ERROR CLIENT 에 빨간 테 #E24B4A (AC-R31-62 노드)', () => {
    const container = renderNode(clientSpan({ status: 'ERROR' }));
    const ring = Array.from(container.querySelectorAll('circle')).find(
      (c) => c.getAttribute('stroke') === '#E24B4A',
    );
    expect(ring).toBeDefined();
  });

  it('TC-R31-80 SERVER · INTERNAL · DB 노드의 위 라벨 · 아래 줄 · title 글자는 고친 전 출력 그대로 (AC-R31-68)', () => {
    const server = renderNode(
      plainSpan('SERVER', 'com.example.OrderController#create', {
        'http.method': 'POST',
        'http.route': '/api/orders',
      }),
    );
    expect(upperLines(server)).toEqual(['OrderController', '#create']);
    expect(lowerTexts(server).map((t) => t.textContent)).toEqual(['POST /api/orders']);
    expect(server.querySelector('div[title]')!.getAttribute('title')).toBe('com.example.OrderController#create');

    const internal = renderNode(plainSpan('INTERNAL', 'com.example.OrderService#place', {}));
    expect(upperLines(internal)).toEqual(['OrderService', '#place']);
    expect(lowerTexts(internal)).toHaveLength(0);
    expect(internal.querySelector('div[title]')!.getAttribute('title')).toBe('com.example.OrderService#place');

    const db = renderNode(
      plainSpan('DB', 'jdbc.execute', {
        'db.statement': '/* OrderMapper.insert */ INSERT INTO orders VALUES (?)',
      }),
    );
    expect(upperLines(db)).toEqual(['jdbc.execute']);
    expect(lowerTexts(db).map((t) => t.textContent)).toEqual(['SQL INSERT']);
    expect(db.querySelector('div[title]')!.getAttribute('title')).toBe('jdbc.execute');
  });

  it('TC-R31-81 속성 없는 CLIENT(옛 · 다른 도구 자료) → 아래 줄 없음 · 위 라벨 ≤ 2줄 · 「undefined」 글자 0 (AC-R31-67 화면 쪽)', () => {
    const container = renderNode(clientSpan({ attributes: {} }));
    expect(lowerTexts(container)).toHaveLength(0);
    const lines = upperLines(container);
    expect(lines.length).toBeGreaterThan(0);
    expect(lines.length).toBeLessThanOrEqual(2);
    expect(container.textContent ?? '').not.toContain('undefined');
  });
});
