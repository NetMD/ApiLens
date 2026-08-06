// [R21] `-D` 옵션 문자열 생성기 — 설계 §7.3 골든 표 B-20~B-23 (US-05).
//
// R21/AC-05-1 verbatim: "원격 설정 화면과 같은 표면에 붙는다 — Setup wizard 화면이 아니다
// (표면 분리 — NFR-08·G-16). wizard 빌더에 새 `-D` 키 배선 0." — 이 테스트도 wizard
// agent-option-builder 를 import 하지 않는다 (표면 분리 확인).
//
// SSOT 실참조: FE 짝 전례(agent-option-builder.test.ts FT-D1 — readFileSync + resolve 로 형제
// 모듈 소스 Read)와 같은 방식으로 AgentConfig.java 를 읽어 "출력 -D 키 ⊆ SSOT 키 집합" 을
// 단언한다 (키 오타 = R5 trace 0건 버그 재발 방지 — 설계 §2.5). agent 모듈은 읽기만 — diff 0.
//
// EXT-003 lock-in 회귀 가드 — 정방향 동사(builds/emits/keeps/matches) 만 사용.
import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  MYBATIS_MAPPER_PROXY,
  buildInstrumentOptionString,
  buildInstrumentOptionTokens,
} from '../lib/instrument-option-generator';
import type { InstrumentOptionInput } from '../lib/instrument-option-generator';

/** 화면 기본값 (UX §4.6 — 파라미터 캡처 켜짐 상태 = 옵션 기본 true / ResultSet 기본 false / 목록 빈 / MyBatis 해제). */
function defaults(patch: Partial<InstrumentOptionInput> = {}): InstrumentOptionInput {
  return {
    captureParams: true,
    captureResultSet: false,
    excludePackages: [],
    mybatisAll: false,
    ...patch,
  };
}

describe('buildInstrumentOptionString — 골든 케이스 (B-20~B-22)', () => {
  it('buildsEmptyStringForAllDefaults — 기본값 그대로면 빈 문자열 (B-20 — C-07 비활성 + U-34 근거)', () => {
    // 기본값과 같은 것을 출력하면 거짓말이 아니라도 소음 — "기본값과 다른 것만" (UX §4.6-1).
    expect(buildInstrumentOptionString(defaults())).toBe('');
  });

  it('buildsGoldenStringWithAllTokens — false,true,[com.acme.batch],on 골든 문자열 (B-21 — 순서·공백·join 정확)', () => {
    const out = buildInstrumentOptionString(
      defaults({
        captureParams: false,
        captureResultSet: true,
        excludePackages: ['com.acme.batch'],
        mybatisAll: true,
      }),
    );
    // 토큰 순서 고정: capture-params → capture-result-set → exclude-packages. 구분자 = 공백 1개.
    expect(out).toBe(
      '-Dapilens.jdbc.capture-params=false -Dapilens.jdbc.capture-result-set=true -Dapilens.instrument.exclude-packages=com.acme.batch,org.apache.ibatis.binding.MapperProxy',
    );
  });

  it('emitsMybatisProxyOnceWhenManuallyPresent — 수기 중복 + 체크 on → 1회만 출력 (B-22)', () => {
    const out = buildInstrumentOptionString(
      defaults({ excludePackages: [MYBATIS_MAPPER_PROXY], mybatisAll: true }),
    );
    const occurrences = out.split(MYBATIS_MAPPER_PROXY).length - 1;
    expect(occurrences).toBe(1);
  });

  it('emitsCaptureParamsTokenOnlyWhenReduced — capture-params 는 false 일 때만 출력', () => {
    expect(buildInstrumentOptionString(defaults({ captureParams: false }))).toBe(
      '-Dapilens.jdbc.capture-params=false',
    );
    expect(buildInstrumentOptionString(defaults({ captureParams: true }))).toBe('');
  });

  it('emitsResultSetTokenOnlyWhenEnabled — capture-result-set 은 true 일 때만 출력', () => {
    expect(buildInstrumentOptionString(defaults({ captureResultSet: true }))).toBe(
      '-Dapilens.jdbc.capture-result-set=true',
    );
  });

  it('buildsCommaJoinedExcludePackages — 목록 항목 콤마 join (UX §4.6-2)', () => {
    const out = buildInstrumentOptionString(
      defaults({ excludePackages: ['com.acme.', 'com.other.'] }),
    );
    expect(out).toBe('-Dapilens.instrument.exclude-packages=com.acme.,com.other.');
  });

  it('keepsInputArrayUnmutated — MyBatis 추가가 입력 배열을 변형하지 않는다', () => {
    const packages = ['com.acme.'];
    buildInstrumentOptionTokens(defaults({ excludePackages: packages, mybatisAll: true }));
    expect(packages).toEqual(['com.acme.']);
  });
});

// ── SSOT 실참조 (B-23) — AgentConfig.java 소스 텍스트가 1차 SSOT (FT-D1 전례 동형) ──────────
describe('SSOT — AgentConfig.java 실참조 (B-23)', () => {
  it('matchesAgentConfigPropKeysFromSsotSource — 출력 -D 키 전수가 PROP_* 리터럴 집합의 부분집합', () => {
    // 경로 기준 = vitest cwd (apilens-ui 루트) — agent-option-builder.test.ts 전례 그대로.
    const ssotPath = resolve(
      process.cwd(),
      '../apilens-agent/src/main/java/io/apilens/agent/config/AgentConfig.java',
    );
    // 파일 부재 = 즉시 실패 (ENOENT throw) — SSOT 참조 끊김 검출기.
    const source = readFileSync(ssotPath, 'utf-8');
    const propKeys = new Set(
      [...source.matchAll(/PROP_\w+\s*=\s*"([^"]+)"/g)]
        .map((m) => m[1])
        .filter((k): k is string => k !== undefined),
    );
    expect(propKeys.size).toBeGreaterThanOrEqual(4);

    // 세 토큰이 전부 나오는 최대 입력으로 -D 키 전수를 뽑는다.
    const tokens = buildInstrumentOptionTokens(
      defaults({
        captureParams: false,
        captureResultSet: true,
        excludePackages: ['com.acme.'],
        mybatisAll: true,
      }),
    );
    expect(tokens).toHaveLength(3);
    const dKeys = tokens.map((t) => t.slice(2, t.indexOf('=')));
    for (const key of dKeys) {
      // 키가 agent 가 실제로 읽는 PROP_* 리터럴과 불일치 → agent 가 옵션을 조용히 무시 (회귀 본체).
      expect(propKeys.has(key), `-D 키 '${key}' 가 AgentConfig PROP_* 집합에 없음`).toBe(true);
    }
  });
});
