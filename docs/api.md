---
title: ApiLens API 계약
owner: maintainer
last-reviewed: 2026-06-23
---

# ApiLens API

운영자 UI / 외부 도구가 호출하는 엔드포인트 계약 명세 — 조회(traces·services·settings·
masking-rules) + ingest + setup + 쓰기(settings·masking-rules). 모든 요청·응답은
`application/json` (UTF-8). 응답은 1KB 초과 시 gzip 압축.

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
- 503 — **유지보수 모드(수신 일시정지) 중** (v0.3.1 신규). 헤더 `Retry-After: 60`, body `{ "error": "서버가 유지보수 중이라 잠시 수신을 멈췄습니다." }`. validate/mask/DB write 전부 skip (service 미호출).

**Response 503 (수신 일시정지 중)**

```json
{ "error": "서버가 유지보수 중이라 잠시 수신을 멈췄습니다." }
```

**주의**

- payload `body`는 raw 그대로 보낼 것. **마스킹은 서버에서 적용** 후 저장.
- 한 batch는 한 trace의 모든 span을 포함하는 것을 권장 (v0.1 trace 요약은 batch 단위로 계산됨).
- **유지보수 모드 503 부작용** (agent 무변경): 운영자가 `POST /v1/maintenance/pause`
  로 수신을 멈추면 이후 agent flush 는 503 을 받는다. agent 는 (현재 구현 기준) 매 flush 마다 503 을
  1회 받고 → 약 1초 후 1회 재시도 → 그래도 503 이면 **해당 batch 를 drop** 하고
  `server error: status=503` warning 을 남긴다. agent 는 `Retry-After` 헤더를 따로 해석하지 않는다
  (server 가 못 막음). 따라서 **유지보수는 수 분 내 짧게 끝내고 즉시 재개** 할 것 — 일시정지가 길수록
  drop 되는 데이터가 늘어난다. 켜둔 채 잊어도 `max-pause cap`(30분) 이 자동 재개하므로 무한 drop 은 없다.

---

## GET /v1/traces — 대시보드 산점도 + 리스트

**Query parameters** (모두 optional)

| 파라미터  | 타입             | 기본 | 설명                                              |
| --------- | ---------------- | ---- | ------------------------------------------------- |
| `service` | string           | —    | service_name 정확 일치                            |
| `since`   | long (millis)    | —    | `start_time >= since`                             |
| `until`   | long (millis)    | —    | `start_time < until`                              |
| `status`  | `OK` \| `ERROR`  | —    | 상태 필터                                         |
| `q`       | string           | —    | **[v0.2 신규]** root_operation **풀 FQCN 부분 일치** 검색. `%`/`_`/`\` 는 서버가 escape — 검색어는 항상 리터럴로만 매칭 (와일드카드 비발동) |
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
- `q` 검색 기준은 저장된 `root_operation` **원본(풀 FQCN)** — UI 의 단축 표기는 표시 전용이며 검색과 무관
- duration 필터는 의도적으로 제공하지 않음 — 필터 축은 status + operation 검색 2종 (설계상 의도)

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
- **스트리밍/리소스 반환값 placeholder** — agent 는 직렬화 시 부수효과 위험 타입(`Resource` ·
  `InputStream` 등)을 직렬화하지 않고 placeholder 로 대체합니다:
  `{"_apilens":"streaming-body-skipped","type":"org.springframework.core.io.support.ResourceRegion"}`.
  `type` 필드 값은 **운영자 본인 앱의 클래스 FQCN** — ApiLens server(운영자 로컬)로만 전송·저장되고
  그 외 외부 송신 경로는 없습니다 (agent 의 유일한 송신 대상 = `-Dapilens.server` 한 곳)

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
- `traceCount` — **[v0.2.0 의미 변경]** 해당 서비스의 **최근 24시간** trace 수 (`start_time >= now − 24h`, 경계값 포함). v0.1 의 누적 전수 카운트가 대용량 DB 에서 매 호출 풀스캔을 유발해 윈도우 한정으로 변경 — 필드명·응답 구조는 무변경
- `healthStatus` — 서버 응답 시점 기준 상태. 대시보드 health dot 색과 1:1 대응:

  | 값 | 조건 (now − `lastSeenAt`) | 의미 |
  |----|---------------------------|------|
  | `active` | ≤ 5분 (또는 clock skew 로 음수) | 정상 수신 중 |
  | `stale` | ≤ 30분 | 최근 수신 끊김 |
  | `inactive` | > 30분 | 장시간 미수신 |
  | `never` | `lastSeenAt == null` | 등록만 됨, trace 0건 |

- 매 호출마다 `services` ⋈ `traces` 집계 — 캐시 없음. v0.2 는 캐싱 대신 **24h 윈도우 한정 카운트 + `(service_name, start_time)` 복합 인덱스 covering** 으로 응답 시간을 확보 (수백만 행 DB 에서도 윈도우 내 행만 스캔).

> **[v0.1.0 변경 — W-01]** 이전 사전 릴리스 빌드의 `lastSeen` (항상 값 있음) 필드는 `lastSeenAt` (nullable) 로 대체되었고 `registeredAt` / `source` / `healthStatus` 3 필드가 추가되었습니다. v0.1.0 이 첫 public release 이므로 공개 버전 간 호환성 영향은 없습니다.
>
> **[v0.2.0 변경]** `traceCount` 의 의미가 "누적 전수" → "최근 24시간" 으로 변경되었습니다 (위 필드 설명 참조). UI 는 라벨을 `Trace 수 (24h)` 로 표기합니다.

---

## DELETE /v1/services/{serviceName} — 서비스 등록 해제

`/services` 화면의 [삭제] 버튼이 호출. **services row 만 제거** — 해당 서비스의
traces / spans / payloads 는 보존되어 Dashboard historical view 에서 계속 조회 가능
(추적 데이터 보존 원칙).

**Request**: path 파라미터 `serviceName` 외 없음.

**Response 204** (본문 없음)

- **멱등** — 존재하지 않는 serviceName 도 204
- 같은 `service_name` 의 trace 가 다시 도착하면 services row 자동 재등록 (`source: "auto"`)

**Errors**: 없음.

---

> ⚠️ **보안 주의 — `/v1/settings` · `/v1/masking-rules` endpoint 는 인증이 필요합니다 (키 설정 시, v0.3.0 적용 완료).**
> v0.3.0 부터 `/v1/**` 는 default-deny 로 보호된다 — API Key 설정 시 토큰 필수, 미설정 시 통과(`ApiKeyAuthFilter`).
> 아래 설정 endpoint 는 보관 기간과 **마스킹 정책을 변경** 할 수 있습니다.
> 특히 마스킹 룰 변경은 **이후 저장되는 payload 내용에 영향** 을 줍니다 (룰을 끄면 이후 ingest 분의 민감 값이 평문 저장).
> ApiLens 는 운영자가 신뢰 네트워크(사내망 · NAS LAN)에서 단독으로 쓰는 도구를 전제로 하므로,
> 키를 설정하지 않은 경우 **server (포트 8765) 를 신뢰할 수 없는 네트워크 · 공용 인터넷에 직접 노출하지 마세요.**
> 외부 노출이 필요하면 API Key 를 설정하거나 reverse proxy 의 인증 · IP 화이트리스트 뒤에 두기를 권장합니다.

## POST /v1/maintenance/cleanup — 보관 기간 즉시 적용 (수동 정리)

설정 페이지 "데이터 관리" 섹션의 [지난 데이터 정리] 버튼이 호출. 스케줄러(매일 04:00)를
기다리지 않고 **보관 기간(retention window)을 즉시 적용** — 보관 기간을 초과한 trace 를
지금 삭제한다. `payloads → spans → traces` 순 **행 단위 배치 DELETE** + PRAGMA 로만 공간을
회수한다 (운영 DB **파일** 삭제/이동/재생성 0 — 운영 DB 파일 보존 원칙).

**Request**: 빈 body `{}` (파라미터 없음).

**Response 200**

```json
{ "deletedTraces": 12345, "freedBytes": 53687091200, "dbSizeBytes": 41943040 }
```

- `deletedTraces` (int): 이번 작업으로 삭제된 **trace 행 수** (span/payload 는 세지 않음).
- `freedBytes` (long): (작업 전 page_count − 작업 후 page_count) × page_size. 음수면 0 으로 하한
  (incremental_vacuum/WAL 상태에 따른 미세 증가 방어).
- `dbSizeBytes` (long): 작업 후 page_count × page_size (현재 DB 논리 크기).

> ⚠️ **cleanup 의 디스크 회수 구조적 한계** (cleanup 은 full VACUUM 을 쓰지 않기 때문):
> - `incremental_vacuum` 은 **tail-only** — free page 가 파일 끝에 모일 때만 회수한다. 중간
>   단편화는 즉시 줄지 않을 수 있다.
> - `wal_checkpoint(TRUNCATE)` 는 **reader 경합(SQLITE_BUSY)에 취약** — 운영자가 화면을 보는
>   중 실행하면 busy 로 부분 실패할 수 있고, 다음 cleanup 에서 자연 재시도된다.
> 따라서 cleanup 의 `freedBytes` 가 삭제량 대비 작게 나올 수 있다 (정상 — 한도이지 결함 아님).
> 중간 단편화까지 회수하려면 `POST /v1/maintenance/optimize` (online full VACUUM = 행 재구성,
> 파일 삭제 0 — v0.3.0 신규) 를 쓴다. 운영 DB 파일 보존 원칙(파일 삭제/이동/재생성 금지) 은 optimize 의 online
> 전체 VACUUM 으로도 위배되지 않는다 — VACUUM 은 같은 파일 안에서 행을 재구성할 뿐 파일을 새로 만들지 않는다.

**Errors**: 없음 (정상 경로). 내부 오류 시 표준 `{ "error": "<message>" }`.

## POST /v1/maintenance/purge — 전체 삭제 (되돌릴 수 없음)

설정 페이지 "데이터 관리" 섹션의 [전체 삭제] 버튼이 호출. **모든 trace/span/payload 를 즉시
영구 삭제** 한다 (cutoff 없이 전부). cleanup 과 동일하게 `payloads → spans → traces` 순 행 단위
배치 DELETE + PRAGMA 로만 회수한다 (운영 DB 파일 보존 원칙 — 파일 삭제 0).

> ⚠️ **파괴적 동작** — 이 endpoint 는 호출 즉시 **모든 추적 데이터가 복구 불가능하게 삭제** 된다.
> 인증은 v0.3.0 default-deny 로 보호된다 (키 설정 시 토큰 필수, 미설정 시 통과). UI 는 확인 모달로
> 보호하지만 API 직접 호출에는 모달이 없다. 키를 설정하지 않은 경우 server (포트 8765) 를
> 신뢰할 수 없는 네트워크에 노출하지 말 것.

**Request**: 빈 body `{}` (파라미터 없음).

**Response 200** (동기 — 삭제 완료까지 응답을 보류한다. 데이터가 크면 **수 분 소요** 가능. 클라이언트
타임아웃은 5분으로 상향되어 있다.)

```json
{ "deletedTraces": 98765, "freedBytes": 107374182400, "dbSizeBytes": 65536 }
```

- 필드 의미는 `POST /v1/maintenance/cleanup` 과 동일 (`deletedTraces` 는 전체 삭제된 trace 수).

**Errors**: 없음 (정상 경로). 내부 오류 시 표준 `{ "error": "<message>" }`.

## POST /v1/maintenance/optimize — 디스크 조각 정리(최적화) (v0.3.0)

설정 페이지 "데이터 관리" 섹션의 [디스크 조각 정리(최적화)] 버튼이 호출. **online 전체 VACUUM** 으로
중간 단편화까지 회수한다 — **삭제 없이 파일 조각만 재구성** (`deletedTraces=0`). VACUUM 은 같은
파일 안에서 행을 재구성할 뿐 파일을 삭제/이동/재생성하지 않으므로 운영 DB 파일 보존 원칙에 위배되지 않는다.

**Request**: 빈 body `{}` (파라미터 없음).

**Response 200**

```json
{ "deletedTraces": 0, "freedBytes": 8388608, "dbSizeBytes": 33554432, "busy": false }
```

- `deletedTraces` (int): 항상 0 (optimize 는 삭제하지 않는다).
- `freedBytes` (long): (작업 전 page_count − 작업 후 page_count) × page_size. 음수면 0 으로 하한.
- `dbSizeBytes` (long): 작업 후 page_count × page_size.
- `busy` (boolean): VACUUM 이 디스크 부족 거부 / `SQLITE_BUSY`·`SQLITE_FULL` 로 부분 실패하면 true.
  정상 회수면 false. busy=true 여도 200 (부분 실패는 한도이지 오류 아님).

**Errors**: 없음 (정상 경로). 내부 오류 시 표준 `{ "error": "<message>" }`.

## GET /v1/maintenance/status — 수신 일시정지 상태 조회 (v0.3.1)

설정 페이지 배지·배너·대시보드가 폴링(15초)으로 호출. 현재 수신 일시정지 상태를 echo 한다.
상태는 **in-memory** 이며 server 재시작 시 `paused=false` 로 복귀한다 (스키마 변경 0, 마이그레이션 없음).

조회 시점에 max-pause cap(30분)이 경과했으면 자가 재개 후 `paused=false` 를 echo 한다.

**Request**: 파라미터 없음 (GET).

**Response 200**

```json
{ "paused": false, "pausedAt": null }
```

```json
{ "paused": true, "pausedAt": 1730000000000 }
```

- `paused` (boolean): 현재 수신 일시정지 여부.
- `pausedAt` (long \| null): 일시정지 시작 epoch millis. `paused=false` 면 `null` (echo 일관성).

**Errors**: 없음 (정상 경로).

## POST /v1/maintenance/pause — 수신 일시정지 (v0.3.1)

설정 페이지 "데이터 관리" 섹션의 [수신 일시정지] 버튼이 호출. 이후 `POST /v1/spans` 는 503 으로
응답한다 (위 ingest 절 참조). **멱등** — 이미 일시정지 중이면 최초 시작 시각을 유지한다.

> 정리(optimize/purge/cleanup)는 **수신을 먼저 일시정지한 뒤** 실행하는 것을 권장한다 (잠금 경합 회피).
> 단 강제는 아니다 — 일시정지 없이도 정리는 동작한다.

**Request**: 빈 body `{}` (파라미터 없음).

**Response 200**

```json
{ "paused": true, "pausedAt": 1730000000000 }
```

- 응답은 `GET /v1/maintenance/status` 와 동일한 `MaintenanceStatusResponse` echo.

**Errors**: 없음 (정상 경로).

## POST /v1/maintenance/resume — 수신 재개 (v0.3.1)

설정 페이지 "데이터 관리" 섹션의 [수신 재개] 버튼이 호출. 수신을 재개하면 `POST /v1/spans` 가
다시 202 를 응답한다. **멱등** — 이미 수신 중이면 그대로 `paused=false`.

**Request**: 빈 body `{}` (파라미터 없음).

**Response 200**

```json
{ "paused": false, "pausedAt": null }
```

**Errors**: 없음 (정상 경로).

> ⚠️ **max-pause cap (30분)** — 운영자가 일시정지를 켜둔 채 잊어도 30분이 지나면 server 가
> **자동으로 수신을 재개** 한다 (안전장치, 수동 resume 과 별개 트리거). 따라서 무한 데이터 drop 은
> 발생하지 않는다. 정상 유지보수는 수 분 내 짧게 끝내는 것을 전제로 한다.

**보안 / 권한** (status/pause/resume 공통): `/v1/maintenance/**` 는 `/v1/**` default-deny 로
**보호** 된다 (v0.3.0 — 키 설정 시 토큰 필수, 미설정 시 통과). `AuthWhitelist` 면제 목록에 등재되지 않는다.

## GET /v1/settings — 설정 조회

설정 페이지 Retention 섹션이 호출. 마지막 cleanup 실행 시각을 동봉한다.

**Request**: 파라미터 없음.

**Response 200**

```json
{ "settings": { "retention.days": 30 }, "lastCleanupAt": 0 }
```

- `settings["retention.days"]` — **resolve 된 유효값**: DB(settings 테이블) 저장 값이 있으면 그 값,
  없으면 yml fallback (`apilens.retention.days`, 기본 30) 이 그대로 내려감 (**DB 저장 값이 yml 보다 우선**)
- `lastCleanupAt` — 마지막 cleanup **실행** 시각 (epoch millis, 삭제 0건이어도 갱신). `0` = 실행 이력 없음
- v0.2 노출 키는 `retention.days` 1개 (cleanup 실행 시각 cron 은 yml 전용 — 과다 노출 금지)

**Errors**: 없음.

---

## PUT /v1/settings — 설정 갱신 (원자)

**Request**

```json
{ "retention.days": 14 }
```

**Response 200** — 갱신 후 상태 (GET 과 동일 형태)

```json
{ "settings": { "retention.days": 14 }, "lastCleanupAt": 0 }
```

- **원자 적용** — body 의 키 전체가 유효할 때만 적용. 하나라도 위반이면 전체 400 + DB 무변경 (부분 적용 0)
- `retention.days` 허용 범위: **정수 1~3650** (10년). 비정수(`1.5`, `"abc"`) / 범위 외 / 미지의 키 → 400
- ⚠️ `retention.days` 를 **줄이면** 다음 cleanup(기본 매일 04:00)에서 새 기준을 초과한 오래된 trace 가 **영구 삭제** 됩니다 (복구 불가)

**Errors**

- 400 — 범위/타입 위반: `{ "error": "retention.days must be an integer between 1 and 3650" }`
- 400 — 미지의 키: `{ "error": "unknown settings key: foo.bar (allowed: retention.days)" }`

---

## GET /v1/masking-rules — 마스킹 룰 목록

설정 페이지 Masking Rules 섹션이 호출. default(빌트인) + custom 전체를 반환한다.

**Request**: 파라미터 없음.

**Response 200**

```json
{ "rules": [
  { "ruleId": 1, "name": "주민번호", "ruleType": "regex", "pattern": "\\d{6}-?\\d{7}",
    "maskStrategy": "partial", "enabled": true, "isDefault": true },
  { "ruleId": 5, "name": "my-api-key", "ruleType": "field_name", "pattern": "x-api-key",
    "maskStrategy": "full", "enabled": true, "isDefault": false }
] }
```

- 정렬: `is_default DESC, rule_id ASC` — **default 4종(주민번호/카드번호/password/token) 상단 고정**
- `ruleType`: `"field_name"` | `"regex"` / `maskStrategy`: `"full"` | `"partial"` | `"hash"` | `"length_only"`
- `isDefault: true` 룰은 **삭제 불가, 토글(비활성)만 가능** (데이터 모델 설계 원칙)

**Errors**: 없음.

---

## POST /v1/masking-rules — custom 룰 생성

**Request**

```json
{ "name": "my-api-key", "ruleType": "field_name", "pattern": "x-api-key",
  "maskStrategy": "full", "enabled": true }
```

**Response 201** — 생성된 룰 1건 (GET 의 rules 요소와 동형)

- `isDefault` 필드는 **요청 DTO 에 존재하지 않음** — 서버가 `is_default=0` 강제 (default 위장 생성 불가)
- `enabled` 생략 시 `true`
- `pattern` 은 저장 전 **정규식 사전 컴파일 검증** — invalid regex 는 DB 에 유입되지 않음
- 룰 변경(생성/토글/삭제)은 **이후 ingest 분부터 적용** — 기존 저장 payload 의 재마스킹은 없음 (재마스킹 경로 자체가 없음)

> ⚠️ **ReDoS 주의** — custom 정규식은 문법(컴파일) 검증만 거치며 **복잡도 검증 없이 그대로 컴파일·적용** 됩니다.
> catastrophic backtracking 패턴(중첩 수량자 등)을 등록하면 ingest·프리뷰 처리가 장시간 점유되어 **서버 응답 지연·불가** 가
> 발생할 수 있습니다. 신뢰 네트워크 전제(키 미설정 시) 에서 패턴 입력은 **운영자 본인 책임** 입니다 —
> 문제 발생 시 해당 룰 비활성(PATCH) 또는 삭제(DELETE)로 해소하세요.

**Errors** (400)

```json
{ "error": "pattern is not a valid regex: Unclosed group near index 9" }
{ "error": "ruleType must be one of: field_name, regex" }
{ "error": "name must not be blank" }
```

- 그 외: `maskStrategy` enum 위반 / `name` > 100자 / `pattern` > 1000자 → 400

---

## PATCH /v1/masking-rules/{ruleId} — enable/disable 토글

**Request**

```json
{ "enabled": false }
```

**Response 200** — 갱신된 룰 1건 (GET 의 rules 요소와 동형)

- body 는 `enabled` **단일 필드만** 허용 — 다른 필드 포함 시 400 (v0.2 는 토글만, 룰 내용 수정 PUT 미제공)
- **default 룰도 토글은 허용** ("비활성만 가능" = 토글 가능)
- 토글은 **핫 리로드** — 서버 재기동 없이 이후 ingest 분부터 반영

**Errors**

- 400 — `{ "error": "only 'enabled' can be updated in v0.2" }`
- 404 — `{ "error": "rule not found" }`

---

## DELETE /v1/masking-rules/{ruleId} — custom 룰 삭제

**Response 204** (본문 없음) — custom 룰 삭제 성공

**Errors**

- **409** — default 룰 삭제 시도 (행은 그대로 보존): `{ "error": "default rule cannot be deleted — disable it instead" }`
- 404 — `{ "error": "rule not found" }`

> 409 채택 사유: 무인증 도구에서 403 은 "인증 실패", 400 은 "요청 형식 오류" 로 오독될 수 있어,
> "리소스 상태/정책상 수행 불가" 의미에 정합한 409 로 확정.

---

## POST /v1/masking-rules/preview — 마스킹 라이브 프리뷰

설정 페이지의 Before/After 프리뷰가 호출. **화면의 토글 상태(저장 전)를 그대로 반영** 해 계산하며,
DB·적용 중 엔진은 일절 변경하지 않는다 ("결재용 신뢰 도구"). 마스킹 계산은 agent/server 와
**같은 공유 엔진** (apilens-common `MaskingEngine`) — FE 재구현 없음, 프리뷰 결과 = 실제 저장 결과.

**Request**

```json
{ "sample": null,
  "contentType": "application/json",
  "ruleStates": [
    { "ruleId": 1, "enabled": false }, { "ruleId": 2, "enabled": true },
    { "ruleId": 3, "enabled": true },  { "ruleId": 4, "enabled": true } ] }
```

- `sample` — null/생략 = **서버 내장 기본 샘플** (default 룰 4종이 전부 반응하는 JSON)
- `contentType` — 생략 시 `application/json`
- `ruleStates` — 화면의 현재 토글 상태 **전체 스냅샷**. 생략 시 DB 저장 상태 그대로 / 미존재 ruleId 는 무시

**Response 200** (기본 샘플 + 4종 전부 활성 시 — 실측 출력)

```json
{ "sample": "{\"ssn\":\"880101-1234567\",\"cardNumber\":\"1234-5678-9012-3456\",\"password\":\"hunter2\",\"token\":\"eyJhbGc\"}",
  "masked": "{\"ssn\":\"880***********\",\"cardNumber\":\"1234***************\",\"password\":\"***\",\"token\":\"***\"}",
  "contentType": "application/json" }
```

- `sample` 은 입력 원문 echo — 기본 샘플 모드에서도 FE 가 Before 박스를 그대로 표시 가능
- `partial` 전략은 값의 앞 1/4 만 보존 (주민번호 14자 → 앞 3자, 카드번호 19자 → 앞 4자 — 위 실측 값)

**Errors** (400)

```json
{ "error": "sample must not be blank" }
{ "error": "sample exceeds 65536 bytes" }
```

- 크기 상한 65,536 bytes = agent `apilens.payload.max-bytes` 기본값과 동일값 정렬

---

> ⚠️ **보안 주의 — `/v1/setup/*` endpoint 는 인증 면제입니다 (v0.3.0 인증 적용 후에도 의도적 무인증).**
> v0.3.0 default-deny 에서 `/v1/setup/**` 는 화이트리스트 면제 경로다 (wizard 가 토큰 발급 전 단계라 면제 불가피).
> 아래 setup endpoint 는 agent jar 다운로드 · 추출 절대경로 · wizard 상태를 인증 없이 노출합니다.
> ApiLens 는 운영자가 신뢰 네트워크(사내망 · NAS LAN)에서 단독으로 쓰는 도구를 전제로 하므로,
> **server (포트 8765) 를 신뢰할 수 없는 네트워크 · 공용 인터넷에 직접 노출하지 마세요.**
> 외부 노출이 필요하면 reverse proxy 의 인증 · IP 화이트리스트 뒤에 두기를 권장합니다.

## GET /v1/setup/state — wizard 완료 상태 조회

UI 가 첫 진입 시 호출 — wizard 자동 오픈 여부 분기.

**Request**: 파라미터 없음.

**Response 200**

```json
{ "completed": true, "completedAt": 1730000000000, "serverUrl": "http://apilens-host:8765" }
```

- 미완료 시: `{ "completed": false, "completedAt": null, "serverUrl": null }`
- `serverUrl` — wizard Step 1 에서 저장한 값. skip 완료(빈 입력) 시 `null`

**Errors**: 없음.

---

## POST /v1/setup/complete — wizard 완료 기록

wizard 마지막 단계가 호출. **멱등** — 재호출 시 `completedAt` / `serverUrl` 갱신.

**Request**

```json
{ "serverUrl": "http://apilens-host:8765", "services": [ { "name": "my-api" } ] }
```

- `serverUrl` — `http://` 또는 `https://` 시작. **빈 문자열/null 은 skip 흐름으로 정상 허용** (200) — `null` 로 정규화 저장
- `services` — wizard 에서 등록한 서비스 이름 목록 (`source: "wizard"` 로 등록). `null`/`[]` 허용 (setup 상태만 갱신)
- service name: 영문/숫자/하이픈/언더스코어 (`^[A-Za-z0-9_-]+$`). 같은 이름 재등록은 무시 (중복 안전)

**Response 200**

```json
{ "completed": true, "completedAt": 1730000000000 }
```

**Errors** (400)

```json
{ "error": "serverUrl must start with http:// or https://" }
{ "error": "service name format invalid" }
```

---

## GET /v1/setup/agent-jar-path — Agent jar 자동 추출 절대경로

server 가 startup 시 임베드된 `apilens-agent.jar` 를
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

**보안 / 권한**: 기존 setup endpoint (`/state` / `/complete`) 와 동일 — 인증 면제 (v0.3.0 인증 적용 후에도 `/v1/setup/**` 화이트리스트 면제).

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

**보안 / 권한**: 기존 setup endpoint 와 동일 — 인증 면제 (v0.3.0 인증 적용 후에도 `/v1/setup/**` 화이트리스트 면제). 임베드 source 는 `AgentJarExtractor.EMBEDDED_RESOURCE` (`classpath:/agent/apilens-agent.jar`) 로 추출 endpoint 와 단일 source.

---

## 공통 오류 응답

모든 에러 본문은 flat `{ "error": "<message>", …컨텍스트 필드 }` 단일 표준 — 중첩
`{ error: { code, message } }` 구조는 쓰지 않는다 (v0.2 신규 endpoint 도 동일).

| Status | 본문 형태                                          | 발생 조건                                                          |
| ------ | -------------------------------------------------- | ------------------------------------------------------------------ |
| 400    | `{ "error": "<message>" }`                         | 잘못된 cursor/limit, ingest 검증, settings 범위·키 위반, 룰 생성·토글 검증, preview 검증 |
| 404    | `{ "error": "...", "traceId"/"spanId": "..." }`    | trace 또는 span 미존재                                              |
| 404    | `{ "error": "rule not found" }`                    | masking rule 미존재 (PATCH/DELETE)                                  |
| 409    | `{ "error": "default rule cannot be deleted — disable it instead" }` | **default 마스킹 룰 삭제 시도** (행 보존)         |

---

## 페이지네이션 메모

`offset` 페이지네이션은 쓰지 않는다. 운영 환경에서 trace는 계속 들어오므로
offset 기준점이 흔들린다. cursor는 마지막 row의 `(start_time, trace_id)`를
base64로 직렬화한 keyset이라 새 데이터가 들어와도 이미 본 페이지 위치는 그대로다.
