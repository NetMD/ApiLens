// [R10] AC-05-12 (D-H10-01 비협상) — Q-08 cross-stack parity 동기.
//
// 동일 입력 (5 필드: serviceName / serverUrl / captureParams / captureResultSet / agentJarPath) →
// 동일 출력 문자열 (token-for-token) 을 backend `AgentOptionBuilder.java` 와 보장한다.
// 양측 단위 테스트 (golden output) 로 정합 검증.
//
// [R10] 시그니처 변경 — 기존 11 it 의 input 에 agentJarPath: null 추가 (golden output 보존).
// 신규 3 it (정상 주입 / null fallback / blank fallback) — Q-08 parity 의무.
//
// 회귀 가드 (반대 방향 lock-in 차단):
//   - hidesToastOnCopyClick / rejectsAgentJarPath 같은 반대 방향 동사 0건
//   - agent-option-builder.ts 가 AGENT_JAR_PATH 잔존 상수 사용 0건
import { describe, expect, it } from 'vitest';
import {
  buildAgentOption,
  buildAgentOptionPreview,
  buildEnvSnippet,
  buildEnvSnippetPreview,
} from '../lib/agent-option-builder';

describe('buildAgentOption — Q-08 cross-stack parity', () => {
  it('정상 입력 (agentJarPath=null fallback) → 5 토큰 모두 포함 + 정확 골든 문자열', () => {
    const out = buildAgentOption({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: null,
    });
    expect(out).toBe(
      '-javaagent:/path/to/apilens-agent.jar -Dapilens.service.name=my-api -Dapilens.server=http://localhost:8765 -Dapilens.jdbc.capture-params=true -Dapilens.jdbc.capture-result-set=false',
    );
  });

  it('serviceName 빈 문자열 → Error throw', () => {
    expect(() =>
      buildAgentOption({
        serviceName: '',
        serverUrl: 'http://localhost:8765',
        captureParams: true,
        captureResultSet: false,
        agentJarPath: null,
      }),
    ).toThrow(/serviceName is required/);
  });

  it('serverUrl 가 http:// / https:// 로 시작 안 함 → Error throw', () => {
    expect(() =>
      buildAgentOption({
        serviceName: 'my-api',
        serverUrl: 'localhost:8765',
        captureParams: true,
        captureResultSet: false,
        agentJarPath: null,
      }),
    ).toThrow(/http:\/\/ or https:\/\//);
  });

  it('captureParams=true / captureResultSet=false → 정확 boolean 표기', () => {
    const out = buildAgentOption({
      serviceName: 'svc_1',
      serverUrl: 'https://apilens-host:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: null,
    });
    expect(out).toContain('-Dapilens.jdbc.capture-params=true');
    expect(out).toContain('-Dapilens.jdbc.capture-result-set=false');
    // boolean 대소문자 정합 — Java toString(boolean) 과 동일한 lowercase.
    expect(out).not.toContain('=True');
    expect(out).not.toContain('=False');
  });

  // [R10] AC-05-12 — Q-08 parity 신규 케이스 3건 (정상 주입 / null fallback / blank fallback).
  // BE AgentOptionBuilderTest 의 buildsCorrectStringWithExtractedAgentJarPath /
  // buildsFallbackStringWhenAgentJarPathIsNull / buildsFallbackStringWhenAgentJarPathIsBlank 와
  // golden output 동일 의무 (token-for-token).
  it('acceptsExtractedAgentJarPath → -javaagent: 토큰에 절대경로 박힘', () => {
    const out = buildAgentOption({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: '/Users/foo/.apilens/apilens-agent.jar',
    });
    expect(out).toBe(
      '-javaagent:/Users/foo/.apilens/apilens-agent.jar -Dapilens.service.name=my-api -Dapilens.server=http://localhost:8765 -Dapilens.jdbc.capture-params=true -Dapilens.jdbc.capture-result-set=false',
    );
  });

  it('acceptsNullAgentJarPathAsFallback → FALLBACK_JAR_PATH 사용 (BE/FE 동일 분기)', () => {
    const out = buildAgentOption({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: null,
    });
    expect(out).toContain('-javaagent:/path/to/apilens-agent.jar');
  });

  it('acceptsBlankAgentJarPathAsFallback → FALLBACK_JAR_PATH 사용', () => {
    const out = buildAgentOption({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: '   ',
    });
    expect(out).toContain('-javaagent:/path/to/apilens-agent.jar');
  });
});

describe('buildAgentOption — 추가 유효성 케이스', () => {
  it('serviceName 형식 위반 (공백/특수문자) → Error throw', () => {
    expect(() =>
      buildAgentOption({
        serviceName: 'my api',
        serverUrl: 'http://localhost:8765',
        captureParams: true,
        captureResultSet: false,
        agentJarPath: null,
      }),
    ).toThrow(/serviceName format invalid/);
  });

  it('serverUrl 빈 문자열 → Error throw', () => {
    expect(() =>
      buildAgentOption({
        serviceName: 'my-api',
        serverUrl: '',
        captureParams: true,
        captureResultSet: false,
        agentJarPath: null,
      }),
    ).toThrow(/serverUrl is required/);
  });

  it('https:// 도 정상 동작', () => {
    const out = buildAgentOption({
      serviceName: 'my-api',
      serverUrl: 'https://apilens-host:8765',
      captureParams: false,
      captureResultSet: true,
      agentJarPath: null,
    });
    expect(out).toContain('-Dapilens.server=https://apilens-host:8765');
    expect(out).toContain('-Dapilens.jdbc.capture-params=false');
    expect(out).toContain('-Dapilens.jdbc.capture-result-set=true');
  });
});

describe('buildAgentOptionPreview — Step 4 미리보기용', () => {
  it('정상 입력 → buildAgentOption 결과와 동일 (fallback path)', () => {
    const out = buildAgentOptionPreview({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: null,
    });
    expect(out).toContain('-javaagent:/path/to/apilens-agent.jar');
  });

  it('정상 입력 + agentJarPath 절대경로 → preview 도 절대경로 사용', () => {
    const out = buildAgentOptionPreview({
      serviceName: 'my-api',
      serverUrl: 'http://localhost:8765',
      captureParams: true,
      captureResultSet: false,
      agentJarPath: '/Users/foo/.apilens/apilens-agent.jar',
    });
    expect(out).toContain('-javaagent:/Users/foo/.apilens/apilens-agent.jar');
  });

  it('입력 부족 시 빈 문자열 반환 (throw X)', () => {
    expect(
      buildAgentOptionPreview({
        serviceName: '',
        serverUrl: '',
        captureParams: false,
        captureResultSet: false,
        agentJarPath: null,
      }),
    ).toBe('');
  });
});

describe('buildEnvSnippet — 실행 환경별 부착 (java -jar / Maven / Gradle / Docker)', () => {
  const base = {
    serviceName: 'vams-prod',
    serverUrl: 'http://192.168.1.39:8765',
    captureParams: true,
    captureResultSet: false,
    agentJarPath: '/opt/apilens-agent.jar',
  };

  it('java -jar — agent 옵션이 -jar 앞 + your-app.jar 로 끝남', () => {
    const s = buildEnvSnippet('java', base);
    expect(s.startsWith('java -javaagent:/opt/apilens-agent.jar ')).toBe(true);
    expect(s).toContain('-Dapilens.server=http://192.168.1.39:8765');
    expect(s.endsWith('-jar your-app.jar')).toBe(true);
  });

  it('Maven — spring-boot.run.jvmArguments 로 감쌈', () => {
    const s = buildEnvSnippet('maven', base);
    expect(s).toContain('mvn spring-boot:run -Dspring-boot.run.jvmArguments="');
    expect(s).toContain('-Dapilens.jdbc.capture-params=true');
    expect(s.endsWith('"')).toBe(true);
  });

  it('Gradle — bootRun jvmArgs 목록', () => {
    const s = buildEnvSnippet('gradle', base);
    expect(s).toContain('bootRun {');
    expect(s).toContain('jvmArgs(');
    expect(s).toContain("'-javaagent:/opt/apilens-agent.jar'");
    expect(s).toContain("'-Dapilens.server=http://192.168.1.39:8765'");
  });

  it('Docker — docker-compose JAVA_TOOL_OPTIONS', () => {
    const s = buildEnvSnippet('docker', base);
    expect(s).toContain('environment:');
    expect(s).toContain('JAVA_TOOL_OPTIONS: "');
    expect(s).toContain('-javaagent:/opt/apilens-agent.jar');
  });

  it('모든 환경이 올바른 agent 키를 공유 (틀린 옛 키 0)', () => {
    for (const env of ['java', 'maven', 'gradle', 'docker'] as const) {
      const s = buildEnvSnippet(env, base);
      expect(s).toContain('-Dapilens.server=');
      expect(s).toContain('-Dapilens.jdbc.capture-params=');
      expect(s).toContain('-Dapilens.jdbc.capture-result-set=');
      // 옛 틀린 키 잔존 0 (NAS dogfooding 회귀 가드)
      expect(s).not.toContain('apilens.server.url');
      expect(s).not.toContain('apilens.capture.');
    }
  });

  it('buildEnvSnippetPreview — 입력 부족 시 빈 문자열 (throw X)', () => {
    expect(buildEnvSnippetPreview('docker', { ...base, serviceName: '' })).toBe('');
  });
});
