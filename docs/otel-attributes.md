# OpenTelemetry Attribute 키 명세

Agent가 채우고 server가 그대로 저장 → UI가 표시할 때 참조하는 `span.attributes`
키 목록. **OpenTelemetry semantic conventions와 호환되는 키만 사용**한다 — 자체
포맷을 만들지 말 것 (나중에 Jaeger export 가능하게 하려는 목표).

저장은 그냥 JSON object. server는 키를 검증하지 않는다 (forward-compat). 단,
agent가 이 명세를 따라야 UI 측 표시 로직이 깔끔해진다.

---

## HTTP (SERVER / CLIENT span)

| 키                   | 타입   | 예시                  | 설명                                 |
| -------------------- | ------ | --------------------- | ------------------------------------ |
| `http.method`        | string | `"POST"`              | 메서드                               |
| `http.url`           | string | `"/api/orders/42"`    | full path or full URL (운영자 가독성) |
| `http.route`         | string | `"/api/orders/{id}"`  | 라우트 패턴 (path variable 추상화)   |
| `http.status_code`   | int    | `200`, `500`          | 응답 상태                            |

`http.url`은 query string 포함해도 OK 단, 마스킹 룰이 query에는 적용되지 **않음**
주의 (현재 마스킹은 payload 본문만). PII가 URL에 들어가면 별도 처리 필요 — v0.2.

---

## DB (DB span)

| 키                    | 타입   | 예시                            | 설명                          |
| --------------------- | ------ | ------------------------------- | ----------------------------- |
| `db.statement`        | string | `"SELECT * FROM orders WHERE..."` | SQL (parameter는 `?`로)        |
| `db.parameters`       | array  | `["42", "alice"]`               | bound parameter 배열          |
| `db.rows_affected`    | int    | `1`, `0`                        | UPDATE/DELETE/INSERT의 영향 row |
| `db.connection`       | string | `"jdbc:postgresql://..."`       | 마스킹 권장 (호스트/계정 노출) |

`db.parameters`는 마스킹 엔진이 적용 — 주민번호/카드번호가 들어오면 알아서 가림.

---

## Exception (status=ERROR span)

| 키                    | 타입   | 예시                             | 설명                              |
| --------------------- | ------ | -------------------------------- | --------------------------------- |
| `exception.type`      | string | `"NullPointerException"`         | 예외 클래스 simple name          |
| `exception.message`   | string | `"Cannot invoke ... on null"`    | 예외 메시지                       |
| `exception.stacktrace`| string | `"java.lang...\n  at ...\n..."` | 멀티라인 stacktrace (원인 사슬 `Caused by:` 포함). agent 가 **4,096자 후미 절단** — 초과분은 잘리고 끝에 `... (truncated)` 가 붙음 |

UI는 status=ERROR span 옆에 stacktrace 박스를 빨갛게 표시 (CLAUDE.md 디자인 결정).

---

## Code location (모든 span 공통, optional)

| 키                | 타입   | 예시                              | 설명                                  |
| ----------------- | ------ | --------------------------------- | ------------------------------------- |
| `code.namespace`  | string | `"com.example.OrderService"`     | 클래스 풀네임                         |
| `code.function`   | string | `"createOrder"`                   | 메서드명                              |

운영자가 코드 어느 지점인지 식별할 때 필요. agent가 ByteBuddy advice로 자동 채움.

---

## SpanKind (span 종류)

각 span 은 다음 5종 중 하나의 `spanKind` 를 가진다 (OpenTelemetry 호환 + UI 전용 1종).

| SpanKind   | 용도                                                              |
| ---------- | ----------------------------------------------------------------- |
| `SERVER`   | 들어오는 요청을 받는 쪽 (HTTP controller 진입 등)                 |
| `CLIENT`   | 외부로 나가는 호출 (HTTP client, 외부 API 호출 등)                |
| `INTERNAL` | 같은 프로세스 안의 내부 작업 (`@Service` 메서드, agent.startup 등)|
| `DB`       | DB 호출 (JDBC PreparedStatement 등). DB 속성 표가 적용되는 span   |
| `UI_EVENT` | 브라우저 측 이벤트 (JS tracer — 향후 사용)                        |

- **`INTERNAL`** span 은 위 HTTP / DB / Exception 표의 키를 그대로 쓸 수 있고, 추가로
  `code.namespace` / `code.function` (Code location 표)로 코드 위치를 식별한다. agent
  startup hello span(`operationName=agent.startup`)도 `INTERNAL` 이다.
- **`UI_EVENT`** 는 브라우저에서 발생하는 이벤트를 위한 종류로, 현재 server/agent 는 채우지
  않는다 (JS tracer 도입 시 사용 예정). enum 값만 미리 정의되어 있다.

---

## v0.2+ 확장 후보 (지금은 사용 안 함)

- `messaging.system`, `messaging.destination` (Kafka, RabbitMQ — v0.2)
- `rpc.service`, `rpc.method` (gRPC — v0.3)
- `feature.flag.key`, `feature.flag.value` (조건부 동작 추적 — v0.3+)

---

## Agent 작업 시 주의

1. 위에 없는 키를 추가해도 server는 받아준다. 다만 UI에서 자동으로 예쁘게
   표시되지 않는다 (raw key/value 표).
2. 키 이름은 **점(`.`) 구분 OpenTelemetry 컨벤션** 따를 것. `httpMethod`나
   `db_statement` 같은 자체 표기 금지.
3. payload(body)와 attributes는 별도 흐름. body는 `payloads` 테이블, attributes는
   `spans.attributes_json`. 큰 데이터는 attributes에 넣지 말고 payload로 보내라.
4. 값에 PII가 들어갈 가능성이 있는 attribute(`db.parameters`, `http.url` 등)는
   마스킹 엔진의 정규식 룰이 적용되도록 텍스트 직렬화 시 일관된 형태 유지.
