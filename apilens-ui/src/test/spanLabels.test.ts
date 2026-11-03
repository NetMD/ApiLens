// [Phase AB / R31] TC-R31-60~72 — lib/spanLabels.ts 순수 함수 시험(설계 부록 A2-5 · 한 행 = it 하나 · it.each 안 씀).
//
// 외부 호출 구간(CLIENT span)의 화면 글자 규칙을 고정한다. 노드 · 카드 · 머리글 세 자리가 이 파일의 함수를 쓴다.
//
// 검증 의무 (정방향 동사 — 비협상 UD-R31-6 의 lock-in 회귀 가드 · R-1 inline 대체(PM 호출 계약 10)):
//   TC-R31-61 · 65 · 66 · 67 = 기획 [보충] AC-R31-25 · AC-R31-28 · AC-R31-30 의 글자 쪽(폭 실측은 실브라우저 스모크 몫).
//
// ★UC-R31-3 판정 하한(28 · 8)은 설계 상수를 import 하지 않고 이 파일 안 글자로 둔다(DV-U-R31-8) —
//   설계가 상수를 바꿔도 기획 하한은 따로 재야 하기 때문이다.
import { describe, expect, it } from 'vitest';
import type { SpanDetail } from '../types/api';
import { shortenOperation } from '../lib/format';
import {
  CLIENT_LABEL_LINE_MAX_CHARS,
  CLIENT_SECONDARY_MAX_CHARS,
  HTTP_KEYS,
  SERVER_ADDRESS_KEY,
  SERVER_PORT_KEY,
  clientCallText,
  fitTwoLines,
  formatHostPort,
  rootHttpDescription,
  truncateEnd,
} from '../lib/spanLabels';

// 기획 UC-R31-3 — 메서드 + 빈칸 + 호스트:포트가 이 글자 수 이하면 통째로 남는다.
const UC_WHOLE_HOST_PORT_MAX = 28;
// 기획 UC-R31-3 — 넘으면 호스트 첫 이 글자 수 이상이 남는다.
const UC_HOST_PREFIX_MIN = 8;

function clientAttrs(method: string, host: string, port: unknown, url: string): Record<string, unknown> {
  return {
    'http.method': method,
    'http.url': url,
    [SERVER_ADDRESS_KEY]: host,
    [SERVER_PORT_KEY]: port,
    'http.status_code': 200,
  };
}

function span(
  spanId: string,
  parentSpanId: string | null,
  spanKind: SpanDetail['spanKind'],
  operationName: string,
  attributes: Record<string, unknown>,
): SpanDetail {
  return {
    spanId,
    parentSpanId,
    serviceName: 'my-api',
    operationName,
    spanKind,
    startTime: 0,
    endTime: 120,
    status: 'OK',
    attributes,
  };
}

/** UC-R31-3 판정 — 시험 안 글자 상수로 하한을 잰다. */
function assertUcLowerBound(result: string, method: string, host: string, hostPort: string): void {
  const head = `${method} ${hostPort}`;
  if (head.length <= UC_WHOLE_HOST_PORT_MAX) {
    expect(result.startsWith(head)).toBe(true);
  } else {
    expect(result.startsWith(`${method} ${host.slice(0, UC_HOST_PREFIX_MIN)}`)).toBe(true);
  }
}

describe('spanLabels — 외부 호출 글자 규칙 [R31]', () => {
  it('TC-R31-60 formatHostPort — IPv4 · IPv6 괄호 · 포트 없음 · -1 · 0 · 문자열 포트는 「:」 없음', () => {
    expect(formatHostPort('192.0.2.39', 1234)).toBe('192.0.2.39:1234');
    expect(formatHostPort('::1', 8080)).toBe('[::1]:8080');
    expect(formatHostPort('[::1]', 8080)).toBe('[::1]:8080');
    expect(formatHostPort('h', undefined)).toBe('h');
    expect(formatHostPort('h', -1)).toBe('h');
    expect(formatHostPort('h', 0)).toBe('h');
    expect(formatHostPort('h', '8080')).toBe('h');
    expect(formatHostPort('h', 80.5)).toBe('h');
    for (const out of [formatHostPort('h', undefined), formatHostPort('h', -1)]) {
      expect(out).not.toContain(':undefined');
      expect(out).not.toContain(':-1');
    }
  });

  it('TC-R31-61 clientCallText — 메서드 · 호스트:포트 · 경로 (BV-R31-03)', () => {
    expect(
      clientCallText(
        clientAttrs('POST', '192.0.2.39', 1234, 'http://192.0.2.39:1234/v1/chat/completions'),
      ),
    ).toBe('POST 192.0.2.39:1234/v1/chat/completions');
  });

  it('TC-R31-62 clientCallText — 포트 키 없음이면 「:포트」 통째로 빠지고 끝 콜론 0 · 스킴 대소문자 무시 (BV-R31-06 · DV-U-R31-9)', () => {
    const noPort: Record<string, unknown> = {
      'http.method': 'GET',
      'http.url': 'ws://h/a',
      [SERVER_ADDRESS_KEY]: 'h',
    };
    const text = clientCallText(noPort);
    expect(text).toBe('GET h/a');
    expect(text).not.toContain('h:/');
    expect(text).not.toContain(':undefined');
    // 대문자 스킴도 「스킴://권한」 을 걷어 낸다 — 걷지 못하면 경로 자리에 URL 이 통째로 붙는다.
    expect(clientCallText(clientAttrs('GET', 'h', 80, 'HTTP://h:80/a'))).toBe('GET h:80/a');
  });

  it('TC-R31-63 clientCallText — 메서드 또는 상대 주소가 없으면 undefined (빈 글자 아님)', () => {
    expect(clientCallText({ [SERVER_ADDRESS_KEY]: 'h', 'http.url': 'http://h/a' })).toBeUndefined();
    expect(clientCallText({ 'http.method': 'GET', 'http.url': 'http://h/a' })).toBeUndefined();
    expect(clientCallText({ 'http.method': '', [SERVER_ADDRESS_KEY]: 'h' })).toBeUndefined();
    expect(clientCallText({})).toBeUndefined();
  });

  it('TC-R31-64 truncateEnd — N 글자는 그대로 · N+1 은 N 글자로 끝 「…」 (경계 P-2)', () => {
    // [R31/DD-R31-21] 노드 아래 줄 상한 값 고정.
    expect(CLIENT_SECONDARY_MAX_CHARS).toBe(31);
    const n = CLIENT_SECONDARY_MAX_CHARS;
    const exact = 'a'.repeat(n);
    const over = 'a'.repeat(n + 1);
    expect(truncateEnd(exact, n)).toBe(exact);
    const cut = truncateEnd(over, n);
    expect(cut).toHaveLength(n);
    expect(cut.endsWith('…')).toBe(true);
    expect(cut.slice(0, n - 1)).toBe('a'.repeat(n - 1));
  });

  it('TC-R31-65 경로 200자 → 31 글자 · 「POST 192.0.2.39:1234」 통째로 남고 끝 「…」 (AC-R31-25 · 28 · BV-R31-01)', () => {
    const path = '/' + 'a'.repeat(199);
    expect(path).toHaveLength(200);
    const full = clientCallText(clientAttrs('POST', '192.0.2.39', 1234, `http://192.0.2.39:1234${path}`));
    expect(full).toBeDefined();
    const shown = truncateEnd(full!, CLIENT_SECONDARY_MAX_CHARS);
    expect(shown).toHaveLength(31);
    expect(shown.startsWith('POST 192.0.2.39:1234')).toBe(true);
    expect(shown.endsWith('…')).toBe(true);
    assertUcLowerBound(shown, 'POST', '192.0.2.39', '192.0.2.39:1234');
  });

  it('TC-R31-66 63자 호스트 → 메서드 + 호스트 첫 8자 이상 + 「…」 · 31 글자 (AC-R31-30 · BV-R31-02)', () => {
    const host = 'h'.repeat(63);
    const full = clientCallText(clientAttrs('GET', host, 443, `https://${host}/v1/x`));
    const shown = truncateEnd(full!, CLIENT_SECONDARY_MAX_CHARS);
    expect(shown).toHaveLength(31);
    expect(shown.startsWith('GET ')).toBe(true);
    expect(shown.endsWith('…')).toBe(true);
    assertUcLowerBound(shown, 'GET', host, `${host}:443`);
  });

  it('TC-R31-67 「GET api.internal.example.com:8443/v1/models」 → 31 글자 · 메서드 그대로 · 호스트 앞부분 · 「…」 (AC-R31-25 · BV-R31-04)', () => {
    const full = clientCallText(
      clientAttrs('GET', 'api.internal.example.com', 8443, 'https://api.internal.example.com:8443/v1/models'),
    );
    expect(full).toBe('GET api.internal.example.com:8443/v1/models');
    const shown = truncateEnd(full!, CLIENT_SECONDARY_MAX_CHARS);
    expect(shown).toHaveLength(31);
    expect(shown).toBe('GET api.internal.example.com:8…');
    assertUcLowerBound(shown, 'GET', 'api.internal.example.com', 'api.internal.example.com:8443');
  });

  it('TC-R31-68 fitTwoLines — 28 이하 한 줄 · 빈칸에서 가름 · 빈칸 없으면 28 에서 · 둘째 줄 넘치면 「…」 · 줄마다 ≤ 28 (AC-R31-31)', () => {
    // [R31/DD-R31-21] 위 라벨 줄마다 상한 값 고정.
    expect(CLIENT_LABEL_LINE_MAX_CHARS).toBe(28);
    const max = CLIENT_LABEL_LINE_MAX_CHARS;
    expect(fitTwoLines('HTTP GET h:80/a', max)).toEqual(['HTTP GET h:80/a', null]);
    expect(fitTwoLines('HTTP POST 192.0.2.39:1234/v1', max)).toEqual(['HTTP POST 192.0.2.39:1234/v1', null]);

    const [l1, l2] = fitTwoLines('HTTP POST 192.0.2.39:1234/v1/chat/completions', max);
    expect(l1).toBe('HTTP POST');
    expect(l2).toBe('192.0.2.39:1234/v1/chat/com…');

    const noSpace = 'x'.repeat(60);
    const [n1, n2] = fitTwoLines(noSpace, max);
    expect(n1).toBe('x'.repeat(28));
    expect(n2).toBe('x'.repeat(27) + '…');

    for (const line of [l1, l2, n1, n2]) {
      expect(line).not.toBeNull();
      expect(line!.length).toBeLessThanOrEqual(max);
    }
  });

  it('TC-R31-69 BV-R31-03 · 04 이름의 shortenOperation 결과 = 원문 (「39:1234#」 · 「com:8443#」 조각 0 · AC-R31-26)', () => {
    const ip = 'HTTP POST 192.0.2.39:1234/v1/chat/completions';
    const dns = 'HTTP GET api.internal.example.com:8443/v1/models';
    expect(shortenOperation(ip)).toBe(ip);
    expect(shortenOperation(dns)).toBe(dns);
    expect(shortenOperation(ip)).not.toContain('39:1234#');
    expect(shortenOperation(dns)).not.toContain('com:8443#');
  });

  it('TC-R31-70 rootHttpDescription — SERVER 뿌리(http.route 있음)는 옮기기 전 출력 「POST /api/orders」 그대로 (AC-R31-68)', () => {
    const spans = [
      span('s1', null, 'SERVER', 'com.example.OrderController#create', {
        'http.method': 'POST',
        'http.route': '/api/orders',
        'http.url': 'http://localhost:8080/api/orders?x=1',
      }),
      span('c1', 's1', 'CLIENT', 'HTTP POST 192.0.2.39:1234/v1/chat/completions', clientAttrs('POST', '192.0.2.39', 1234, 'http://192.0.2.39:1234/v1/chat/completions')),
    ];
    expect(rootHttpDescription(spans, 'com.example.OrderController#create')).toBe('POST /api/orders');
    // http.route 없이 full URL 만 있으면 pathname — 옮기기 전 갈래 그대로.
    const urlOnly = [span('s2', null, 'SERVER', 'X#y', { 'http.method': 'GET', 'http.url': 'http://localhost:8080/a/b' })];
    expect(rootHttpDescription(urlOnly, 'X#y')).toBe('GET /a/b');
  });

  it('TC-R31-71 rootHttpDescription — CLIENT 뿌리는 호스트까지 · 포트 키 없음 갈래 · 속성 없으면 rootOperation (AC-R31-37)', () => {
    const op = 'HTTP POST 192.0.2.39:1234/v1/chat/completions';
    const withPort = [span('c1', null, 'CLIENT', op, clientAttrs('POST', '192.0.2.39', 1234, 'http://192.0.2.39:1234/v1/chat/completions'))];
    expect(rootHttpDescription(withPort, op)).toBe('POST 192.0.2.39:1234/v1/chat/completions');

    const noPort = [span('c2', null, 'CLIENT', 'HTTP GET h/a', { 'http.method': 'GET', 'http.url': 'ws://h/a', [SERVER_ADDRESS_KEY]: 'h' })];
    const noPortText = rootHttpDescription(noPort, 'HTTP GET h/a');
    expect(noPortText).toBe('GET h/a');
    expect(noPortText).not.toContain(':undefined');

    const bare = [span('c3', null, 'CLIENT', op, {})];
    expect(rootHttpDescription(bare, op)).toBe(op);
  });

  it('TC-R31-72 키 상수 — 상대 주소 · 상대 포트 글자와 HTTP_KEYS 차례 6 (AC-R31-32 · 41)', () => {
    expect(SERVER_ADDRESS_KEY).toBe('server.address');
    expect(SERVER_PORT_KEY).toBe('server.port');
    expect(HTTP_KEYS).toEqual([
      'http.method',
      'http.url',
      'server.address',
      'server.port',
      'http.route',
      'http.status_code',
    ]);
  });
});
