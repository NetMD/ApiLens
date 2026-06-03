---
phase: H
ac: AC-01-1, AC-06-1, AC-06-2, AC-06-3, AC-06-4, AC-06-5, AC-06-6
scope: 사용자 명시 비협상 결정 (V-USER-H2)
claude-md: CLAUDE.md '아키텍처 핵심 원칙' / '데이터 모델 (5개 테이블, 변경 신중히)'
---

# ApiLens Setup 가이드

> 운영자가 직접 깔고 첫 trace 까지 따라갈 수 있게 만든 친절한 안내입니다. 브라우저에서 Setup wizard 를 따라가도 동일한 흐름이에요.

## 도입 흐름 요약

ApiLens 도입은 다음 4단계로 진행됩니다. 한국 SI 운영자가 docs 안 읽고도 따라갈 수 있도록 wizard 가 동일한 흐름을 제공해요.

1. ApiLens server 를 운영망에 설치하고 실행해요 (기본 포트 8765).
2. 브라우저로 `http://apilens-host:8765` 에 접속하면 Setup wizard 가 자동으로 열려요 (처음 실행 시에만).
3. Wizard 4단계를 따라가서 JVM 옵션 한 줄을 만들어요.
4. 자기 앱 JVM 옵션에 붙여넣고 재기동하면 첫 trace 가 `/services` 화면에 표시돼요.

Wizard 가 자동으로 안 열리면 헤더 우측 `[+]` 버튼이나 `/services` 페이지의 `[+ Add service]` 버튼을 눌러 다시 진입할 수 있어요.

## Wizard 4단계 재현

직접 옵션을 만들고 싶거나 wizard 를 건너뛴 경우 아래 표를 참고해 같은 결과를 만들 수 있어요.

### Step 1 — Server URL

| 항목 | 값 |
|------|----|
| 라벨 | `Server URL` |
| placeholder | `http://your-apilens-host:8765` |
| 안내 | 운영망에서는 사용자 앱이 접근 가능한 IP/hostname 을 입력해 주세요 |
| 유효성 | 비어 있지 않고 `http://` 또는 `https://` 로 시작 |

### Step 2 — Service Name

| 항목 | 값 |
|------|----|
| 라벨 | `Service Name` |
| placeholder | `my-api` |
| 안내 | 사용자 앱을 구분할 이름을 입력해 주세요 (영문/숫자/하이픈/언더스코어) |
| 유효성 | 정규식 `^[A-Za-z0-9_-]+$` |

### Step 3 — Capture Options

| 항목 | 값 |
|------|----|
| 라벨 | `Capture Options` |
| 토글 1 | `JDBC 파라미터 캡처` (default ON) |
| 토글 2 | `JDBC ResultSet 캡처` (default OFF) |
| 안내 | 원하는 옵션만 켜 두세요. 나중에 변경할 수 있어요 |

### Step 4 — JVM 옵션 (생성된 결과 예시)

```
-javaagent:/path/to/apilens-agent.jar -Dapilens.service.name=my-api -Dapilens.server=http://apilens-host:8765 -Dapilens.jdbc.capture-params=true -Dapilens.jdbc.capture-result-set=false
```

각 토큰 의미:

- `-javaagent:/path/to/apilens-agent.jar` — agent jar 경로. server jar 가 첫 실행 시 `~/.apilens/agent.jar` 에 풀어둬요.
- `-Dapilens.service.name=` — 사용자 앱 식별 이름 (영문/숫자/하이픈/언더스코어).
- `-Dapilens.server=` — ApiLens server 의 접근 가능 URL.
- `-Dapilens.jdbc.capture-params=` — JDBC PreparedStatement 파라미터 캡처 여부 (`true` / `false`).
- `-Dapilens.jdbc.capture-result-set=` — JDBC ResultSet 캡처 여부 (`true` / `false`).

옵션 한 줄을 자기 앱 JVM 옵션에 붙여넣고 재기동하면 `/services` 화면에 service 가 표시돼요.

## 트러블슈팅

### agent 부착 후 trace 가 안 보여요

다음 항목을 차례로 확인해 주세요:

1. Server URL 이 사용자 앱에서 접근 가능한 hostname/IP 인지 확인해요 (운영망 방화벽 / hosts 파일 차이로 hostname 이 다른 경우가 잦아요).
2. agent jar 의 경로가 사용자 앱 실행 환경에서 정확한지 확인해요. `-javaagent:` 다음 경로가 실제 파일 위치와 일치해야 해요.
3. 사용자 앱이 JVM 옵션 적용 후 재기동되었는지 확인해요. agent 는 premain 으로 부착되므로 재기동 없이는 작동하지 않아요.
4. 사용자 앱 로그에서 `apilens` 키워드로 warn / error 메시지를 검색해요. server 연결 실패 / port 차단 같은 단서가 보일 수 있어요.

위 4단계로 해결되지 않으면 server 측 로그 (`apilens-server` stdout) 에서 trace 수신 시점에 warn 이 찍히는지 확인해 주세요. agent 는 호스트 앱에 절대 throw 하지 않도록 설계되어 있어서, 문제는 대부분 네트워크 / 경로 / 재기동 누락 중 하나에요.

### 포트 8765 가 이미 쓰이고 있어요

ApiLens server 의 `application.yml` (또는 `application.properties`) 에서 `server.port` 값을 다른 포트로 변경하고, Wizard Step 1 의 Server URL 도 동일하게 맞춰 주세요.

예: 8766 으로 변경 시 → `http://apilens-host:8766`

agent 의 `-Dapilens.server=` 도 같은 포트로 갱신해야 trace 가 정상 전송돼요.

### Wizard 를 다시 열고 싶어요

처음 실행 시에만 자동으로 열려요. 다시 열려면 다음 두 진입점 중 하나를 사용해요:

- 헤더 우측 `[+]` 버튼 클릭 → wizard 진입
- `/services` 페이지 우상단 `[+ Add service]` 버튼 클릭 → wizard 진입

Wizard 를 여러 번 열어 다른 service 를 추가로 등록할 수 있어요. 같은 service_name 을 다시 등록해도 안전해요 (중복 등록은 무시).

### Service 를 삭제했는데 trace 가 안 사라져요

이건 의도된 동작이에요. `/services` 의 [삭제] 버튼은 services row 만 제거하고, 해당 service 의 traces / spans / payloads 는 보존해요. Dashboard 의 historical view 에서는 계속 조회 가능해요.

같은 `service_name` 의 trace 가 다시 도착하면 services row 가 자동으로 다시 만들어져요 (자동 재등록).
