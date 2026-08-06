---
title: ApiLens API 운영 서사
owner: maintainer
last-reviewed: 2026-08-04
---

# ApiLens API

> **정식 API 계약은 자동 생성 스펙이 단일 진실 출처(SSOT)입니다.**
> server 를 띄운 뒤 브라우저로 **`/swagger-ui`** (인터랙티브 문서) 또는 **`/v3/api-docs`** (OpenAPI JSON)
> 를 열면, 컨트롤러에서 자동 생성된 최신 요청·응답 스키마·상태 코드를 그대로 확인·시험 호출할 수 있습니다.
> 이 문서는 자동 스펙이 담기 어려운 **운영 서사(왜 이렇게 동작하는지, 무엇을 조심해야 하는지)** 만 보조로 남깁니다.
> 필드 단위 계약(요청/응답 필드 표)은 손으로 다시 적지 않습니다 — 코드와 어긋나는 stale 을 막기 위해 자동 스펙으로 일원화했습니다.

기준 호스트: `http://localhost:8765` (단일 jar 기본 포트). 모든 요청·응답은 `application/json` (UTF-8),
응답은 1KB 초과 시 gzip 압축됩니다.

> **`/swagger-ui` · `/v3/api-docs` 는 인증 없이 열립니다** — API Key 를 설정한 기동 상태에서도 문서 경로는
> 토큰 없이 접근할 수 있습니다(의도된 면제). 문서 열람 자체는 보호 대상이 아니며, 아래 인증 절이 설명하는
> `/v1/**` 관리·조회 API 호출만 토큰을 요구합니다.

---

## 인증 헤더 전제 (API Key)

`APILENS_AUTH_API_KEY` 환경변수(또는 `-Dapilens.auth.api-key` 시스템 프로퍼티)로 토큰을 설정하면, 그 시점부터
`/v1/**` 관리·조회 API 는 `Authorization: Bearer <토큰>` 헤더를 요구합니다. 헤더가 없거나 틀리면
`401 { "error": "unauthorized" }` 를 반환합니다.

- **면제(무토큰 허용) 경로**: setup wizard(`/v1/setup/**`) · agent 적재(`POST /v1/spans`) ·
  헬스체크(`/actuator/health`) · 정적 자산과 SPA 화면 · API 문서(`/swagger-ui`, `/v3/api-docs`).
- 토큰 미설정 시에는 무인증으로 동작하고 기동 시 경고 로그를 1회 남깁니다 — 기존 환경이 그대로 깨지지 않습니다.
- 토큰은 server 기동 옵션으로만 두며 DB 에 저장하지 않습니다 (키 교체 = server 재시작).
- 토큰은 평문(HTTP)으로 전송됩니다. 신뢰 네트워크(사내망·NAS LAN)에서만 쓰고, 외부 노출이 필요하면
  reverse proxy(nginx 등)로 TLS 를 종단하세요. **server(포트 8765)를 공용 인터넷에 직접 노출하지 마세요.**

---

## 에이전트 적재(`POST /v1/spans`)와 유지보수 503 부작용

Java agent 가 span 배치를 보낼 때 쓰는 경로입니다 (UI 는 호출하지 않음). payload `body` 는 raw 그대로 보내고,
**마스킹은 서버가 적용한 뒤 저장**합니다 (아래 마스킹 절 참조).

**202 응답 본문은 "추가만 허용" 계약입니다** — 기존 두 필드(`accepted`·`traces`)의 이름·타입·의미는 바뀌지 않고,
새 필드는 추가만 됩니다. 서비스에 원격 계측 설정이 저장돼 있으면 202 본문에 `instrumentConfig` 필드가 함께
실립니다 — **설정이 없는 서비스는 필드 자체가 없습니다**(있어도 없어도 소비 측이 깨지지 않는 부재 허용형).
구 agent 는 2xx 본문을 읽지 않으므로 영향이 없고, 재배포는 표준 순서(collector 먼저 → agent 나중)로 끊김 없이
진행됩니다.

**유지보수 모드(수신 일시정지) 중 동작** — 운영자가 `POST /v1/maintenance/pause` 로 수신을 멈추면 이후 적재 요청은
`503` + `Retry-After: 60` 헤더로 응답하고, validate/mask/DB write 를 전부 건너뜁니다 (service 미호출, 저장 0).

- agent 는 (현재 구현 기준) 매 flush 마다 503 을 1회 받고 → 약 1초 후 1회 재시도 → 그래도 503 이면
  **해당 batch 를 drop** 하고 `server error: status=503` 경고를 남깁니다. agent 는 `Retry-After` 헤더를 따로
  해석하지 않습니다.
- 따라서 **유지보수는 수 분 내 짧게 끝내고 즉시 재개**하세요 — 일시정지가 길수록 drop 되는 데이터가 늘어납니다.
  켜둔 채 잊어도 max-pause cap(30분)이 자동 재개하므로 무한 drop 은 없습니다.
- 상태는 in-memory 입니다 — server 재시작 시 항상 수신 중(`paused=false`)으로 복귀합니다.

---

## 원격 계측 설정(`/v1/services/{serviceName}/instrument-config`)

서비스별 "원하는 계측 설정"을 server 에 저장해 두면, 그 서비스 agent 의 다음 적재 202 응답에 실려 전달되어
**JVM 재시작 없이** 적용됩니다 (PUT 저장 / GET 조회 / DELETE 철회 — 정확한 스키마는 `/swagger-ui` 자동 스펙 참조).
설정 화면은 아직 없으며 curl 로 설정합니다 — 항목·동작 원칙·안전 경계는
[Agent 옵션 문서의 "원격 계측 설정" 절](./agent-options.md)이 단일 안내처입니다. 운영 관점 요점만 남깁니다:

- **줄이는 방향만** — agent 는 JVM 시작 `-D` 값을 기준점으로, 그 이하로 줄이는 지시와 기준점으로 되돌리는 지시만
  적용하고 확대 지시는 버립니다(판정은 agent 안에서 — server 를 신뢰하지 않습니다).
- **철회(DELETE)는 즉시 복원이 아닙니다** — 응답에 설정이 더 이상 실리지 않을 뿐, agent 에 이미 적용된 값은
  그대로입니다. 되돌리려면 복귀 값을 명시한 PUT 또는 JVM 재시작.
- **전파는 적재 응답 편승입니다** — 트래픽 0 서비스·수신 일시정지 중에는 적용이 늦어집니다(늦는 방향은 항상
  "예전 계측 상태 유지" 쪽).
- 이 경로는 `/v1/**` 보호 묶음입니다 — 키 설정 시 토큰 필수. **무인증 폴백 환경에서는 LAN 신뢰 전제**입니다.

---

## 마스킹은 서버에서 적용됩니다 (적용 시점)

- agent 는 raw payload 를 보내고, **서버가 ingest 시점에 마스킹을 적용해 저장**합니다. 조회(`.../payloads`)로
  받는 `body` 는 이미 마스킹된 결과이며, 서버가 다시 마스킹하지 않습니다.
- 룰 변경(생성/토글/삭제)은 **이후 ingest 분부터** 반영됩니다 — 이미 저장된 payload 의 재마스킹은 없습니다
  (재마스킹 경로 자체가 없음). 룰 변경은 서버 재기동 없이 핫 리로드됩니다.
- 빌트인 default 룰 4종(주민번호/카드번호/password/token)은 **삭제 불가, 비활성 토글만 가능**합니다.
- **ReDoS 주의**: custom 정규식은 저장 시점에만 복잡도(제한 시간 내 시험 매칭)를 검사합니다. 이미 저장된 룰이나
  적재 핫패스는 가드 대상이 아니므로, 신뢰 네트워크 전제에서 패턴 입력은 운영자 책임입니다.

---

## 정리(cleanup / purge / optimize)의 디스크 회수 구조적 한계

`POST /v1/maintenance/cleanup`(보관 기간 즉시 적용) · `.../purge`(전체 삭제, 되돌릴 수 없음) ·
`.../optimize`(online 전체 VACUUM) 모두 **운영 DB 파일을 삭제/이동/재생성하지 않습니다** — 같은 파일 안에서
행을 지우거나 재구성할 뿐입니다 (운영 DB 파일 보존 원칙).

- `cleanup`/`purge` 는 `incremental_vacuum`(파일 끝의 free page 만 회수) + `wal_checkpoint(TRUNCATE)`(reader 경합 시
  busy 로 부분 실패 가능, 다음 정리에서 재시도) 로만 공간을 회수합니다. 그래서 `freedBytes` 가 삭제량 대비 작게
  나올 수 있습니다 (정상 — 한도이지 결함 아님).
- 중간 단편화까지 회수하려면 `optimize`(online 전체 VACUUM = 행 재구성)를 씁니다. VACUUM 은 작업 중 원본 크기만큼
  임시 공간을 쓰므로, 가용 디스크가 부족하면 거부하거나 `busy=true` 로 부분 실패를 알립니다 (예외를 던지지 않음).
- 정리 전 **수신을 먼저 일시정지**하면 SQLite write 잠금 경합을 피할 수 있습니다 (권장 — 강제 아님).

---

## 마스킹 라이브 프리뷰 (결재용 신뢰 도구)

`POST /v1/masking-rules/preview` 는 **화면의 토글 상태(저장 전)를 그대로 반영**해 Before/After 를 계산하며,
DB·적용 중 엔진을 일절 변경하지 않습니다. 마스킹 계산은 agent/server 와 **같은 공유 엔진**(apilens-common)을
쓰므로, **프리뷰 결과 = 실제 저장 결과**입니다 (FE 재구현 없음). 룰을 켜고 끄며 즉시 확인할 수 있어, 마스킹 정책을
결재·검토하는 신뢰 도구로 씁니다.

---

## 공통 오류 응답 표준

모든 에러 본문은 flat `{ "error": "<message>", …컨텍스트 필드 }` 단일 표준입니다 — 중첩
`{ error: { code, message } }` 구조는 쓰지 않습니다.

- `400` — 요청 검증 실패(잘못된 cursor/limit, ingest 검증, settings 범위·키 위반, 룰 생성·토글 검증, preview 검증).
- `404` — trace/span 미존재(`traceId`/`spanId` 컨텍스트 동봉) 또는 masking rule 미존재(`{ "error": "rule not found" }`).
- `409` — default 마스킹 룰 삭제 시도(행 보존). 무인증 도구에서 403(인증 실패)·400(형식 오류)으로 오독되지 않도록,
  "리소스 상태/정책상 수행 불가" 의미에 정합한 409 로 확정했습니다.
- `503` — 유지보수 모드 중 적재 거절(위 ingest 절 참조).

정확한 상태 코드·본문 스키마는 `/swagger-ui` 자동 스펙에서 확인하세요.

---

## 페이지네이션 메모 (cursor keyset)

`GET /v1/traces` 는 `offset` 페이지네이션을 쓰지 않습니다. 운영 환경에서 trace 는 계속 들어오므로 offset 기준점이
흔들리기 때문입니다. cursor 는 마지막 row 의 `(start_time, trace_id)` 를 base64url 로 직렬화한 keyset 이라, 새
데이터가 들어와도 이미 본 페이지 위치는 그대로 유지됩니다. 디코딩 실패 시 400 을 반환합니다.

---

## setup endpoint 무인증 주의

setup wizard 경로(`/v1/setup/**`)는 인증 면제입니다 (wizard 가 토큰 발급 전 단계라 면제 불가피). 이 경로는 agent jar
다운로드·추출 절대경로·wizard 상태를 인증 없이 노출합니다. 신뢰 네트워크에서만 쓰고, 외부 노출이 필요하면 reverse
proxy 뒤에 두세요.
