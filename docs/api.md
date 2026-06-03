# ApiLens API (v0.1)

운영자 UI / 외부 도구가 호출하는 read 엔드포인트 4개와 ingest 엔드포인트 1개의
계약 명세. 모든 요청·응답은 `application/json` (UTF-8). 응답은 1KB 초과 시 gzip 압축.

기준 호스트: `http://localhost:8765` (단일 jar 기본 포트).

---

## POST /v1/spans — 에이전트 → 서버 ingest

Java agent가 span 배치를 보낼 때 사용. UI는 호출하지 않음.

**Request**

```json
{
  "spans": [
    {
      "spanId": "01HKQ...A",
      "traceId": "01HKQ...T",
      "parentSpanId": null,
      "serviceName": "checkout",
      "operationName": "POST /api/orders",
      "spanKind": "SERVER",
      "startTime": 1730000000000,
      "endTime": 1730000000120,
      "status": "OK",
      "attributes": {
        "http.method": "POST",
        "http.status_code": 200
      },
      "payloads": [
        {
          "direction": "IN",
          "contentType": "application/json",
          "body": "{\"orderId\":42}",
          "sizeBytes": 14,
          "truncated": false
        }
      ]
    }
  ]
}
```

**Response 202**

```json
{ "accepted": 1, "traces": 1 }
```

- `accepted`: 영속화된 span 수
- `traces`: 이 batch에 포함된 distinct trace_id 개수

**Errors**

- 400 — `spans` 비어있거나 필수 필드 누락 (`spanId`, `traceId`, `spanKind`, `status`, `operationName`, `serviceName`, `endTime >= startTime`)

**주의**

- payload `body`는 raw 그대로 보낼 것. **마스킹은 서버에서 적용** 후 저장.
- 한 batch는 한 trace의 모든 span을 포함하는 것을 권장 (v0.1 trace 요약은 batch 단위로 계산됨).

---

## GET /v1/traces — 대시보드 산점도 + 리스트

**Query parameters** (모두 optional)

| 파라미터  | 타입             | 기본 | 설명                                              |
| --------- | ---------------- | ---- | ------------------------------------------------- |
| `service` | string           | —    | service_name 정확 일치                            |
| `since`   | long (millis)    | —    | `start_time >= since`                             |
| `until`   | long (millis)    | —    | `start_time < until`                              |
| `status`  | `OK` \| `ERROR`  | —    | 상태 필터                                         |
| `limit`   | int              | 100  | 1~500. 501 이상은 silently 500으로 cap. <1은 400. |
| `cursor`  | string           | —    | 이전 응답의 `nextCursor`. 디코딩 실패 시 400.     |

**Response 200**

```json
{
  "traces": [
    {
      "traceId": "01HKQ...T",
      "rootOperation": "POST /api/orders",
      "serviceName": "checkout",
      "startTime": 1730000000000,
      "durationMs": 120,
      "status": "OK",
      "spanCount": 5,
      "hasError": false
    }
  ],
  "nextCursor": "MTczMDAwMDAwMDAwMDow..."
}
```

- 정렬: `start_time DESC, trace_id DESC` (tie-breaker)
- `nextCursor` `null` → 다음 페이지 없음
- cursor 인코딩: `base64url("{startTime}:{traceId}")` no-padding

---

## GET /v1/traces/{traceId} — trace 상세 (노드 그래프)

**Response 200**

```json
{
  "trace": {
    "traceId": "01HKQ...T",
    "rootOperation": "POST /api/orders",
    "serviceName": "checkout",
    "startTime": 1730000000000,
    "durationMs": 120,
    "status": "OK",
    "spanCount": 2,
    "hasError": false
  },
  "spans": [
    {
      "spanId": "01HKQ...A",
      "parentSpanId": null,
      "serviceName": "checkout",
      "operationName": "POST /api/orders",
      "spanKind": "SERVER",
      "startTime": 1730000000000,
      "endTime": 1730000000120,
      "status": "OK",
      "attributes": {
        "http.method": "POST",
        "http.status_code": 200
      }
    },
    {
      "spanId": "01HKQ...B",
      "parentSpanId": "01HKQ...A",
      "serviceName": "checkout",
      "operationName": "INSERT orders",
      "spanKind": "DB",
      "startTime": 1730000000010,
      "endTime": 1730000000080,
      "status": "OK",
      "attributes": {
        "db.statement": "INSERT INTO orders ..."
      }
    }
  ]
}
```

- spans는 **평면 배열** — 트리는 `parentSpanId`로 UI가 구성 (React Flow 호환)
- 정렬: `start_time ASC, span_id ASC`
- `attributes`는 parse된 object. parse 실패 시 `{"_raw": "<원본 string>"}`로 fallback

### root span 식별

UI는 trace의 root span을 다음 규칙으로 식별한다 — 서버 응답에서 `rootSpanId`를
도출하는 방법:

```typescript
const rootSpan = trace.spans.find(s => s.parentSpanId === null)
const rootSpanId = rootSpan?.spanId ?? null
```

jq에서는 다음과 같이 추출 (root 없으면 빈 문자열로 graceful 실패):

```bash
ROOT_SPAN_ID=$(curl -s "http://localhost:8765/v1/traces/${TRACE_ID}" \
  | jq -r '.spans | map(select(.parentSpanId == null))[0].spanId // empty')
```

- 정상 trace는 root span이 정확히 1개
- spans 배열 정렬(start_time ASC)에 의존하지 말 것 — clock skew 가능
- 반드시 `parentSpanId === null` 필터링
- 서버 응답의 `rootSpanId` 필드를 우선 사용하고, 없을 때만 위 fallback 적용

**Errors**

- 404 — trace 없음:
  ```json
  { "error": "trace not found", "traceId": "missing-id" }
  ```

---

## GET /v1/traces/{traceId}/spans/{spanId}/payloads — payload lazy load

UI에서 노드 클릭 시 호출. trace 상세는 spans만 반환하므로 payload는 별도 조회.

**Response 200**

```json
{
  "payloads": [
    {
      "direction": "in",
      "contentType": "application/json",
      "body": "{\"user\":\"alice\",\"password\":\"***\"}",
      "sizeBytes": 37,
      "truncated": false
    },
    {
      "direction": "out",
      "contentType": "application/json",
      "body": "{\"ok\":true}",
      "sizeBytes": 11,
      "truncated": false
    }
  ]
}
```

- `body`는 ingest 시점에 마스킹 적용된 결과 — 서버는 다시 마스킹하지 않음
- `direction`: `"in"` | `"out"` (소문자)
- payload 0개여도 200 with `{ "payloads": [] }` (404 아님)

**Errors**

- 404 — trace/span 조합이 없으면:
  ```json
  { "error": "span not found", "traceId": "...", "spanId": "..." }
  ```

---

## GET /v1/services — 대시보드 서비스 셀렉터

**Response 200**

```json
{
  "services": [
    { "name": "auth",     "registeredAt": 1729999000000, "lastSeenAt": 1729999800000, "source": "auto",   "traceCount": 320,  "healthStatus": "active" },
    { "name": "checkout", "registeredAt": 1729900000000, "lastSeenAt": 1730000123000, "source": "wizard", "traceCount": 1248, "healthStatus": "stale" },
    { "name": "report",   "registeredAt": 1729800000000, "lastSeenAt": null,          "source": "wizard", "traceCount": 0,    "healthStatus": "never" }
  ]
}
```

- 정렬: `name ASC`
- `registeredAt` — 최초 등록 시각 (epoch millis). wizard 등록 또는 첫 trace 도착 시점
- `lastSeenAt` — 가장 최근 trace 수신 시각 (epoch millis). **wizard 로 등록만 하고 아직 trace 가 안 들어온 서비스는 `null`**
- `source` — 등록 경로. `"wizard"` (setup wizard 등록) 또는 `"auto"` (첫 trace 도착 시 자동 등록)
- `traceCount` — 해당 서비스의 누적 trace 수
- `healthStatus` — 서버 응답 시점 기준 상태. 대시보드 health dot 색과 1:1 대응:

  | 값 | 조건 (now − `lastSeenAt`) | 의미 |
  |----|---------------------------|------|
  | `active` | ≤ 5분 (또는 clock skew 로 음수) | 정상 수신 중 |
  | `stale` | ≤ 30분 | 최근 수신 끊김 |
  | `inactive` | > 30분 | 장시간 미수신 |
  | `never` | `lastSeenAt == null` | 등록만 됨, trace 0건 |

- 매 호출마다 `services` ⋈ `traces` 집계 — v0.1은 캐시 없음. 트래픽 많아지면 v0.2에서 캐싱 추가.

> **[v0.1.0 변경 — W-01]** 이전 사전 릴리스 빌드의 `lastSeen` (항상 값 있음) 필드는 `lastSeenAt` (nullable) 로 대체되었고 `registeredAt` / `source` / `healthStatus` 3 필드가 추가되었습니다. v0.1.0 이 첫 public release 이므로 공개 버전 간 호환성 영향은 없습니다.

---

> ⚠️ **보안 주의 — `/v1/setup/*` endpoint 는 인증이 없습니다 (v0.3 인증 phase 까지).**
> 아래 setup endpoint 는 agent jar 다운로드 · 추출 절대경로 · wizard 상태를 인증 없이 노출합니다.
> ApiLens 는 운영자가 신뢰 네트워크(사내망 · NAS LAN)에서 단독으로 쓰는 도구를 전제로 하므로,
> **server (포트 8765) 를 신뢰할 수 없는 네트워크 · 공용 인터넷에 직접 노출하지 마세요.**
> 외부 노출이 필요하면 reverse proxy 의 인증 · IP 화이트리스트 뒤에 두기를 권장합니다.

## GET /v1/setup/agent-jar-path — Agent jar 자동 추출 절대경로

[R10] AC-05-4 (D-H10-01 비협상) — server 가 startup 시 임베드된 `apilens-agent.jar` 를
사용자 home (`~/.apilens/apilens-agent.jar`) 으로 자동 추출. wizard Step 4 가 이 endpoint
로 절대경로를 받아 JVM 옵션 박스에 박는다.

**Request**: 파라미터 없음.

**Response 200** (정상 추출)

```json
{ "path": "/Users/foo/.apilens/apilens-agent.jar" }
```

**Response 200** (추출 실패 — NFR-02 fallback)

```json
{ "path": null }
```

- `path=null` 도 HTTP 200 (404 아님). 추출 실패는 silent log warning + extractedPath=null.
- wizard 는 `path=null` 시 fallback placeholder (`/path/to/apilens-agent.jar`) 사용 + 작은 경고 안내 표시.

**Errors**: 없음 (NFR-02 — server 기동 정상이면 항상 200).

**보안 / 권한**: 기존 setup endpoint (`/state` / `/complete`) 와 동일 — 인증 없음 (v0.3 인증 phase 이연).

---

## GET /v1/setup/agent-jar — Agent jar 다운로드

임베드된 `apilens-agent.jar` 를 브라우저 다운로드로 내려준다. `agent-jar-path` 와 보완 관계:

- **`agent-jar-path`** — server 와 대상 앱이 **같은 장비**일 때 (절대경로를 바로 `-javaagent:` 에 사용)
- **`agent-jar` (본 endpoint)** — server 와 대상 앱이 **다른 장비**일 때. 예: ApiLens server 가 NAS 에 떠 있고 운영자는 다른 PC 브라우저로 대시보드 접속 → server 호스트의 절대경로는 쓸 수 없으므로, jar 를 받아 대상 앱 장비로 옮긴 뒤 그 로컬 경로로 `-javaagent:` 지정.

**Request**: 파라미터 없음. (경로 파라미터를 받지 않음 — 고정된 1개 임베드 산출물만 제공하므로 path traversal 표면 0.)

**Response 200** (정상)

```
Content-Type: application/octet-stream
Content-Disposition: attachment; filename="apilens-agent.jar"

<jar 바이너리>
```

**Response 404** (agent jar 미임베드)

- agent shadowJar 가 이 server jar 에 빌드되지 않은 경우 (`:apilens-agent:shadowJar` → `:apilens-server:processResources` 미수행).
- `agent-jar-path` 가 `path=null/200` 으로 분기하는 것과 의도적으로 다름 — **다운로드할 파일이 실제로 없으면 진짜 404**.

**보안 / 권한**: 기존 setup endpoint 와 동일 — 인증 없음 (v0.3 인증 phase 이연). 임베드 source 는 `AgentJarExtractor.EMBEDDED_RESOURCE` (`classpath:/agent/apilens-agent.jar`) 로 추출 endpoint 와 단일 source.

---

## 공통 오류 응답

| Status | 본문 형태                                          | 발생 조건                                |
| ------ | -------------------------------------------------- | ---------------------------------------- |
| 400    | `{ "error": "<message>" }`                         | 잘못된 cursor, 잘못된 limit, ingest 검증 |
| 404    | `{ "error": "...", "traceId"/"spanId": "..." }`    | trace 또는 span 미존재                   |

---

## 페이지네이션 메모

`offset` 페이지네이션은 쓰지 않는다. 운영 환경에서 trace는 계속 들어오므로
offset 기준점이 흔들린다. cursor는 마지막 row의 `(start_time, trace_id)`를
base64로 직렬화한 keyset이라 새 데이터가 들어와도 이미 본 페이지 위치는 그대로다.
