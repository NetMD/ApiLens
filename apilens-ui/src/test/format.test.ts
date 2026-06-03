import { describe, expect, it } from 'vitest';
import {
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
