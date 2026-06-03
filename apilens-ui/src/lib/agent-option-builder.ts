// [R10] AC-05-9 / AC-05-12 (D-H10-01 비협상) — Q-08 cross-stack parity 동기.
//
// Setup wizard Step 4 의 JVM 옵션 한 줄 생성기. backend AgentOptionBuilder.java 와
// token-for-token 동일 출력 (5 파라미터 시그니처 + FALLBACK_JAR_PATH 동일 token).
//
// [R10] 시그니처 변경:
//   기존 4 필드 (serviceName / serverUrl / captureParams / captureResultSet)
//   → 5 필드 (+ agentJarPath: string | null)
//
// agentJarPath 가 null 또는 blank 일 때 FALLBACK_JAR_PATH 사용 (NFR-02 fallback). BE 와 동일 분기.
//
// 사용자 명시 비협상 결정. 토큰 순서는 절대 변경 금지:
//   1. -javaagent:{agentJarPath || FALLBACK_JAR_PATH}
//   2. -Dapilens.service.name={serviceName}
//   3. -Dapilens.server={serverUrl}
//   4. -Dapilens.jdbc.capture-params={true|false}
//   5. -Dapilens.jdbc.capture-result-set={true|false}
// 구분자: single space (" ").
//
// 회귀 가드 grep:
//   정방향: agentJarPath 인터페이스 + 사용 ≥ 2 hit / FALLBACK 상수 정확 1 hit
//   반대 (lock-in 금지): 이전 상수명 (placeholder path 잔존) 0 hit 회귀 차단

/** [R10] AC-05-6 — Fallback placeholder when agentJarPath is null/blank (NFR-02 정합). */
/** BE FALLBACK_JAR_PATH 와 token-for-token 동일 (Q-08 parity). */
const FALLBACK_JAR_PATH = '/path/to/apilens-agent.jar';

/** Service name 유효성 — backend `^[A-Za-z0-9_-]+$` 와 동일. */
const SERVICE_NAME_RE = /^[A-Za-z0-9_-]+$/;

export interface AgentOptionInput {
  serviceName: string;
  serverUrl: string;
  captureParams: boolean;
  captureResultSet: boolean;
  /** [R10] AC-05-9 — server 자동 추출 절대경로. null/blank 시 FALLBACK_JAR_PATH 사용. */
  agentJarPath: string | null;
}

/**
 * agent JVM 옵션 토큰 배열 (순서 고정). {@link buildAgentOption} 과
 * {@link buildEnvSnippet} 의 공용 소스 — 환경별 스니펫이 같은 토큰을 재사용한다.
 *
 * @throws {Error} serviceName 비어 있거나 형식 위반
 * @throws {Error} serverUrl 비어 있거나 http(s):// prefix 없음
 */
export function buildAgentOptionTokens(input: AgentOptionInput): string[] {
  const { serviceName, serverUrl, captureParams, captureResultSet, agentJarPath } = input;

  if (serviceName === undefined || serviceName === null || serviceName.trim() === '') {
    throw new Error('serviceName is required');
  }
  if (!SERVICE_NAME_RE.test(serviceName)) {
    throw new Error('serviceName format invalid');
  }
  if (serverUrl === undefined || serverUrl === null || serverUrl.trim() === '') {
    throw new Error('serverUrl is required');
  }
  if (!(serverUrl.startsWith('http://') || serverUrl.startsWith('https://'))) {
    throw new Error('serverUrl must start with http:// or https://');
  }

  // [R10] AC-05-6 — null/blank fallback (BE FALLBACK_JAR_PATH 와 동일 token).
  const jarPath =
    agentJarPath === null || agentJarPath.trim() === ''
      ? FALLBACK_JAR_PATH
      : agentJarPath;

  // ⚠️ -D 키는 agent 가 실제로 읽는 키와 반드시 일치해야 한다 (SSOT):
  //    apilens-agent AgentConfig.java (PROP_SERVER / PROP_CAPTURE_PARAMS /
  //    PROP_CAPTURE_RESULT_SET) + docs/agent-options.md. 키가 틀리면 agent 가
  //    옵션을 조용히 무시하고 default 로 떨어진다 (예: server URL → localhost).
  //    Q-08 parity(FE==BE)만으로는 이 불일치를 못 잡는다 — 양쪽이 똑같이 틀릴 수 있음.
  // Token order fixed — Q-08 cross-stack parity 의무.
  return [
    `-javaagent:${jarPath}`,
    `-Dapilens.service.name=${serviceName}`,
    `-Dapilens.server=${serverUrl}`,
    `-Dapilens.jdbc.capture-params=${captureParams}`,
    `-Dapilens.jdbc.capture-result-set=${captureResultSet}`,
  ];
}

/** JVM `-javaagent:` 옵션 한 줄 (토큰 공백 join). */
export function buildAgentOption(input: AgentOptionInput): string {
  return buildAgentOptionTokens(input).join(' ');
}

/**
 * 입력이 정확하지 않아도 throw 없이 미리보기용 빈 문자열을 반환한다.
 * Step 4 박스에 입력 부족 시 빈 문자열로 표시 + [복사] 버튼 disabled 트리거.
 */
export function buildAgentOptionPreview(input: AgentOptionInput): string {
  try {
    return buildAgentOption(input);
  } catch {
    return '';
  }
}

// ── 실행 환경별 부착 스니펫 ────────────────────────────────────────────────
// agent 토큰(-javaagent + -D)은 모든 환경 공통. 환경마다 다른 건 "그 토큰을
// 어디에 어떻게 끼우느냐" 뿐이다. (NAS dogfooding 교훈: pom <jvmArguments> 는
// mvn spring-boot:run 전용이라 Docker java -jar 엔 적용 안 됨 → JAVA_TOOL_OPTIONS 필요.)

/** wizard Step 4 환경 선택 탭. */
export type RunEnv = 'java' | 'maven' | 'gradle' | 'docker';

/** 탭 렌더 순서/라벨 (단일 출처). */
export const RUN_ENVS: ReadonlyArray<{ id: RunEnv; label: string }> = [
  { id: 'java', label: 'java -jar' },
  { id: 'maven', label: 'Maven' },
  { id: 'gradle', label: 'Gradle' },
  { id: 'docker', label: 'Docker' },
];

/** 환경별 한 줄 주의사항 (운영자 오용 차단 — dogfooding 교훈 반영). */
export function runEnvNote(env: RunEnv): string {
  switch (env) {
    case 'java':
      return 'agent 옵션은 반드시 -jar 앞에 와야 합니다. your-app.jar 를 실제 jar 로 바꿔 주세요.';
    case 'maven':
      return 'pom 의 <jvmArguments> 는 mvn spring-boot:run 에만 적용됩니다 (빌드된 jar 를 java -jar 로 띄우면 적용 안 됨).';
    case 'gradle':
      return 'build.gradle(.kts) 의 bootRun 에 추가한 뒤 ./gradlew bootRun 으로 실행하세요.';
    case 'docker':
      return 'agent jar 가 컨테이너에서 보여야 합니다 (volumes 마운트 또는 이미지에 포함). server 주소는 컨테이너에서 도달 가능한 IP — localhost 가 아니라 호스트/LAN IP 입니다.';
  }
}

/**
 * 선택한 실행 환경에 맞는 부착 스니펫을 만든다. 토큰은 {@link buildAgentOptionTokens}
 * 재사용 (키/순서 동일). 입력이 유효하지 않으면 throw — 미리보기는 {@link buildEnvSnippetPreview}.
 */
export function buildEnvSnippet(env: RunEnv, input: AgentOptionInput): string {
  const tokens = buildAgentOptionTokens(input);
  const oneLine = tokens.join(' ');
  switch (env) {
    case 'java':
      return `java ${oneLine} -jar your-app.jar`;
    case 'maven':
      return `mvn spring-boot:run -Dspring-boot.run.jvmArguments="${oneLine}"`;
    case 'gradle': {
      const list = tokens.map((t) => `        '${t}'`).join(',\n');
      return [
        '// build.gradle — bootRun task 에 추가',
        'bootRun {',
        '    jvmArgs(',
        list,
        '    )',
        '}',
      ].join('\n');
    }
    case 'docker':
      return [
        '# docker-compose.yml — services.<your-app>.environment 에 추가',
        'environment:',
        `  JAVA_TOOL_OPTIONS: "${oneLine}"`,
      ].join('\n');
  }
}

/** throw 없는 미리보기 — 입력 부족 시 빈 문자열. */
export function buildEnvSnippetPreview(env: RunEnv, input: AgentOptionInput): string {
  try {
    return buildEnvSnippet(env, input);
  } catch {
    return '';
  }
}
