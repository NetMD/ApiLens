import { describe, expect, it } from 'vitest';
import {
  formatBytes,
  formatDuration,
  formatJsonPretty,
  shortenOperation,
  truncateBody,
} from '../lib/format';

describe('shortenOperation', () => {
  it('FQCN + #method 는 simple name + method 로 줄인다', () => {
    expect(shortenOperation('com.example.sampleapp.UserController#create')).toBe(
      'UserController#create',
    );
  });

  it('# 가 없으면 원본 그대로 반환', () => {
    expect(shortenOperation('agent.startup')).toBe('agent.startup');
  });

  it('class 부분에 . 가 없으면 (이미 simple name) 원본 그대로 반환', () => {
    expect(shortenOperation('FooBar#x')).toBe('FooBar#x');
  });

  it('빈 문자열은 빈 문자열 그대로', () => {
    expect(shortenOperation('')).toBe('');
  });
});

describe('formatDuration', () => {
  it('음수는 0ms로 fallback', () => {
    expect(formatDuration(-100)).toBe('0ms');
  });

  it('NaN은 0ms로 fallback', () => {
    expect(formatDuration(NaN)).toBe('0ms');
  });

  it('0은 0ms', () => {
    expect(formatDuration(0)).toBe('0ms');
  });

  it('1000ms 미만은 정수 ms (반올림)', () => {
    expect(formatDuration(999)).toBe('999ms');
  });

  it('1000ms 임계값은 1.0s', () => {
    expect(formatDuration(1000)).toBe('1.0s');
  });

  it('1500ms는 소수 1자리 1.5s', () => {
    expect(formatDuration(1500)).toBe('1.5s');
  });

  it('60000ms 미만 상한은 60.0s', () => {
    expect(formatDuration(59999)).toBe('60.0s');
  });

  it('60000ms 임계값은 1m 0s', () => {
    expect(formatDuration(60000)).toBe('1m 0s');
  });

  it('61500ms는 1m 1s (Math.floor 정수 초)', () => {
    expect(formatDuration(61500)).toBe('1m 1s');
  });

  it('3661000ms는 61m 1s', () => {
    expect(formatDuration(3661000)).toBe('61m 1s');
  });
});

describe('formatJsonPretty', () => {
  it('JSON 객체 문자열을 2-space pretty로 변환', () => {
    expect(formatJsonPretty('{"a":1}')).toBe('{\n  "a": 1\n}');
  });

  it('JSON 배열 문자열을 2-space pretty로 변환', () => {
    expect(formatJsonPretty('[1,2,3]')).toBe('[\n  1,\n  2,\n  3\n]');
  });

  it('parse 실패 시 원본 반환', () => {
    expect(formatJsonPretty('not json')).toBe('not json');
  });

  it('빈 문자열은 빈 문자열', () => {
    expect(formatJsonPretty('')).toBe('');
  });
});

// [R26/AC-R26-42] 기대값 쪽 줄바꿈 없는 공백. formatBytes 가 쓰는 상수를 그대로 가져오지 않고
// 여기서 따로 적는다 — 함수가 보통 공백으로 되돌아가도 이 시험이 그것을 잡아야 하기 때문이다.
// (같은 상수를 import 하면 양쪽이 같이 틀려도 초록으로 통과한다.)
const NBSP = ' ';

describe('formatBytes', () => {
  it('음수는 0 B로 fallback', () => {
    expect(formatBytes(-1)).toBe(`0${NBSP}B`);
  });

  it('NaN은 0 B로 fallback', () => {
    expect(formatBytes(NaN)).toBe(`0${NBSP}B`);
  });

  it('0은 0 B', () => {
    expect(formatBytes(0)).toBe(`0${NBSP}B`);
  });

  it('1024 미만은 바이트 그대로', () => {
    expect(formatBytes(512)).toBe(`512${NBSP}B`);
  });

  it('1024는 1 KB (정수면 소수 생략)', () => {
    expect(formatBytes(1024)).toBe(`1${NBSP}KB`);
  });

  it('1536은 1.5 KB (소수 1자리)', () => {
    expect(formatBytes(1536)).toBe(`1.5${NBSP}KB`);
  });

  it('41943040은 40 MB', () => {
    expect(formatBytes(41943040)).toBe(`40${NBSP}MB`);
  });

  it('53687091200은 50 GB (계약 예시 freedBytes)', () => {
    expect(formatBytes(53687091200)).toBe(`50${NBSP}GB`);
  });

  it('1 TB 단위까지 환산', () => {
    expect(formatBytes(1099511627776)).toBe(`1${NBSP}TB`);
  });

  // [R26/AC-R26-42] (U3) AC 원문: "숫자와 단위 사이가 **줄바꿈 없는 공백** · 짝 시험의 공백
  // 단언도 같이 바뀐다" — 위 단언들은 문자열 전체를 견주므로 어느 글자가 틀렸는지 안 알려준다.
  // 이 시험은 공백 한 글자만 집어 코드포인트로 단언한다. 환산 갈래(1024 이상)와
  // 바이트 갈래(1024 미만) 둘 다 본다 — 한쪽만 고치는 것이 실제로 있었던 빈틈이다.
  it('usesANonBreakingSpaceBetweenTheNumberAndTheUnit — 숫자와 단위를 잇는 공백은 U+00A0', () => {
    const converted = formatBytes(53687091200); // "50 GB" 갈래
    const rawBytes = formatBytes(512); // "512 B" 갈래
    const zero = formatBytes(0); // fallback 갈래

    // 전제 단언 — 애초에 공백이 한 개씩 들어 있어야 아래 대조가 뜻을 가진다.
    expect(converted.length).toBe(5);
    expect(rawBytes.length).toBe(5);
    expect(zero.length).toBe(3);

    expect(converted.charCodeAt(2)).toBe(0x00a0);
    expect(rawBytes.charCodeAt(3)).toBe(0x00a0);
    expect(zero.charCodeAt(1)).toBe(0x00a0);
  });
});

describe('truncateBody', () => {
  it('빈 문자열은 truncated false', () => {
    expect(truncateBody('')).toEqual({ display: '', truncated: false });
  });

  it('정확히 max 길이는 truncated false', () => {
    const s = 'a'.repeat(5120);
    expect(truncateBody(s)).toEqual({ display: s, truncated: false });
  });

  it('max+1 길이는 truncated true', () => {
    const s = 'a'.repeat(5121);
    const r = truncateBody(s);
    expect(r.truncated).toBe(true);
    expect(r.display.length).toBe(5120);
  });

  it('max 한참 초과 시 max까지 잘림', () => {
    const r = truncateBody('a'.repeat(10000));
    expect(r.truncated).toBe(true);
    expect(r.display.length).toBe(5120);
  });
});
