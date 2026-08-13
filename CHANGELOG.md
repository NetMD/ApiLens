# Changelog

All notable changes to ApiLens will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

다음 release 후보가 누적되는 영역입니다. "(candidate)" 표시 항목은 아직 구현 전인 후보입니다.

### Added

- (candidate) `JdbcParamSerializer` 의 `java.time` 타입 확장 (LocalDate / LocalDateTime / LocalTime / ZonedDateTime).

### Security

- (candidate) **공유 마스킹 엔진 ReDoS 근본 차단(linear-time 엔진)** — 실행 deadline 에 더해, 정규식 매칭 자체를 backtracking 없는 linear-time 엔진(RE2/j 류)으로 바꾸는 근본 방어는 별도 후보로 남습니다.
- (candidate) JDBC PreparedStatement 파라미터의 이름 기반 마스킹 보강 — 현재 PAYLOAD IN 키가 parameterIndex 라 이름 기반 룰이 매칭되지 않는 한계 개선.

## [0.6.1] - 2026-08-13

> **collector(server) 전용 라운드.** **agent 를 다시 배포할 필요가 없습니다** — agent jar 도, 대상 앱 JVM 재시작도 그대로 두세요. **스키마 마이그레이션 없음** (DB 파일 변경 0). collector jar 만 교체하고 재기동하면 됩니다. agent 버전 라벨은 계속 `0.6.0` 으로 표시되는 것이 정상입니다(이번에 agent 를 바꾸지 않았다는 뜻).

### BREAKING CHANGES

_이 release 에는 하위호환을 깨는 변경이 없습니다._ API 경로·요청·응답의 **필드 구성이 그대로**입니다(추가·삭제·타입 변경 0). 새 endpoint 도 없고 스키마 변경도 없습니다. 다만 **응답에 실리는 값 몇 가지가 달라집니다** — 계측 분석 화면의 payload 용량이 실제 저장 크기 기준으로 내려가고, 정리 후 "확보한 공간" 숫자가 커지며, 그동안 비어 있던 마지막 정리 시각이 채워집니다. 화면 코드는 바뀌지 않았고 숫자의 기준만 정확해진 것이라 하위호환을 깨지 않습니다.

### Added

- **정리 후 빈 공간 회수량 확대** — 야간 정리가 끝난 뒤 비어 있는 페이지를 **정해진 몫만큼 반복해서** OS 에 돌려줍니다. 이전에는 한 번 부를 때 한 페이지씩만 진행돼 사실상 회수가 안 보였습니다. 그 결과 설정 페이지의 [지난 데이터 정리] 를 눌렀을 때 나오는 **"약 X 확보" 숫자가 눈에 띄게 커집니다**(오류가 아니라 원래 그랬어야 하는 값입니다). 회수량은 그동안 쌓인 양에 달려 있어 이 문서가 수치를 단정하지 않습니다. 오래 밀려 있던 빈 공간은 한 번에 다 돌아오지 않으므로 `docs/api.md` 의 「밀린 빈 공간을 한 번에 돌려받기」 절차를 1회 실행하는 것을 권합니다(**작업 전 DB 파일 백업 권장**). 돌려받은 공간은 영구적이지 않습니다 — 트래픽이 늘면 파일은 다시 자랍니다.
- **고아 기록 자동 정리 (이틀에 걸쳐 확인)** — 어느 흐름에도 붙지 못한 기록(요약 행이 없는 span)을 야간 정리가 정리합니다. **첫 밤에 후보로 적어 두고, 다음 밤에도 여전히 고아인 것만 지웁니다** — 요약이 늦게 도착한 기록을 죽이지 않기 위한 유예입니다. **수동 [지난 데이터 정리] 는 이 정리를 하지 않습니다**(연달아 눌러도 고아 기록은 그대로입니다). 되돌릴 수 없는 수동 조작인 [전체 삭제] 만 유예 없이 그 자리에서 함께 지웁니다. 이 정리는 **증상을 치우는 것이지 고아가 생기는 원인을 고치는 것이 아닙니다** — 원인 해소는 이후 버전 과제로 남습니다.

### Changed

- **계측 분석의 절감 예측 기준을 "실제 저장 크기" 로 교체** — 클래스별 payload 용량과 "빼면 이렇게 돼요" 절감량을, 자르기 전 원본 크기가 아니라 **디스크에 실제로 저장된 본문 길이**로 계산합니다. 본문이 잘려 저장된 기록에서 두 값이 갈리기 때문에, 이전 화면은 그런 기록이 많은 클래스의 절감량을 **실제보다 크게** 보여 줬습니다. 화면 라벨과 표 구성은 그대로이고 숫자만 내려갑니다. **클래스 간 순위가 바뀔 수 있으므로**, 이전에 이 화면을 보고 제외 대상을 정했다면 한 번 다시 확인하시길 권합니다. 저장된 데이터·수집 방식은 바뀌지 않았습니다(계산 기준만 바뀜).
- **정리 시각 기록 방식** — 마지막 정리 시각을 담는 행이 사라져 있으면 이전에는 갱신이 조용히 실패해 설정 페이지에 "정리 이력 없음" 이 계속 남았습니다. 이제 그 행을 **다시 만들면서 기록**하고, 조회 시점에 행이 없으면 서버 로그에 경고를 남깁니다. ⚠️ 행이 왜 사라졌는지는 **아직 규명하지 못했습니다** — "고쳤다" 가 아니라 **"다시 생기면 로그로 알 수 있게 했다"** 입니다.
- **문서 정정 2건** — (a) `incremental_vacuum` 의 한도를 "파일 끝의 free page 만 회수" 로 적었던 설명을 **"한 번에 정해진 몫만 회수"** 로 바로잡았습니다(`docs/api.md` · 위 0.6.0 항목 · 코드 주석). (b) 진입점 없는 흐름 차단 옵션이 없애는 범위를 "배치 워커" 로만 적었던 설명을, **진입점 밖에서 시작되는 흐름 전반**(메시지 소비자·기동 작업·요청에서 갈라진 별도 스레드 포함)으로 넓혀 적었습니다(`README.md` · `docs/agent-options.md` · 위 0.6.0 항목).
- **버전 0.6.0 → 0.6.1** (collector jar / UI 표시 라벨). **agent 버전은 `0.6.0` 그대로입니다** — 이번에 agent 를 바꾸지 않았기 때문이며, 서비스 목록의 agent 버전 라벨이 `0.6.0` 으로 보이는 것이 정상입니다.

## [0.6.0] - 2026-08-06

> **agent + server + UI 라운드** (v0.4.0 이후 두 번째 agent 변경). 운영망(NAS 등)에 **새 agent jar 재배포 + 대상 앱 JVM 재시작이 필요**합니다(재배포 순서: collector 먼저 → agent 나중 — 구 agent 는 새 202 응답 필드를 읽지 않아 무중단). **스키마 마이그레이션 1건 추가** — ⚠️ DB 마이그레이션은 되돌릴 수 없습니다. 0.6.0 으로 한 번 올린 DB 파일을 이전 collector 로 다시 열지 마세요. **올리기 전 DB 파일 백업을 권합니다.**

### BREAKING CHANGES

_이 release 에는 하위호환을 깨는 변경이 없습니다._ `POST /v1/spans` 적재 202 응답은 기존 두 필드(`accepted`·`traces`)의 이름·타입·의미가 그대로이고, 새 필드는 설정 있는 서비스에만 추가로 실립니다(없으면 필드 자체 생략 — 구 agent·기존 소비자 영향 0). 새 계측 옵션(진입점 없는 흐름 차단)은 기본 꺼짐 opt-in 이라 켜지 않으면 동작이 이전과 완전히 같고, 원격 계측 설정은 저장한 서비스에만 적용됩니다. 스키마 변경은 테이블 하나를 새로 만드는 것뿐이라 기존 테이블·데이터에 영향이 없습니다.

⚠️ 다만 **DB 마이그레이션은 되돌릴 수 없습니다** — 위 머리글의 백업 권고를 따르세요.

### Added

- **진입점 없는 흐름 차단 옵션 (`apilens.instrument.require-entry-root`, 기본 꺼짐)** — 켜면 진입점(들어오는 요청을 받는 controller 계층)이 아닌 곳에서 시작되려는 흐름을 만들지 않습니다. 조상 제외로 말단이 새 시작점이 되어 조각 흐름이 쏟아지는 문제를 원천에서 만들지 않는 레버입니다. **켜면 진입점 밖에서 시작되는 흐름이 통째로 사라지며, 그것이 이 옵션의 목적(의도된 동작)입니다 — 그래서 기본값이 꺼짐입니다.** (배치 워커(@Scheduled/@Async 류)가 대표적인 예이고, 메시지 소비자·기동 작업·요청에서 갈라진 별도 스레드도 함께 사라집니다 — 사라지는 범위를 배치 워커로만 적었던 것은 0.6.1 에서 정정했습니다.) 켜지 않으면 동작이 이전과 완전히 같습니다. agent 시작 알림(agent 버전 표시)은 옵션과 무관하게 유지됩니다.
- **원격 계측 설정 채널 (재시작 없이, 줄이는 방향만)** — 서비스별 "원하는 계측 설정"을 server 에 저장(`PUT/GET/DELETE /v1/services/{서비스명}/instrument-config`)하면, 적재 202 응답에 실려 agent 에 전달되어 JVM 재시작 없이 적용됩니다. 설정 항목은 4가지뿐: JDBC 파라미터 캡처 끄기 · JDBC 결과 캡처 끄기 · 진입점 없는 흐름 차단 켜기 · 이름 그대로 개별 제외 목록(gateExcludes — **weaving 으로 못 빼던 MyBatis mapper 를 화면 이름 그대로 제외 가능**). **agent 는 JVM 시작 `-D` 값을 기준점으로 줄이는 방향(및 시작값 복귀)만 적용하고 확대 지시는 버립니다**(판정은 agent 안 — server 를 신뢰하지 않음). 원격 값은 메모리에만 있어 재시작 시 시작 `-D` 값으로 복원됩니다. 설정은 아래의 원격 계측 설정 화면에서 하거나 API(curl)로도 할 수 있습니다.
- **원격 계측 설정 화면 (Services 표의 [계측 설정] 버튼)** — 위 원격 계측 설정 채널을 화면에서 씁니다. 서비스별로 편집·조회·철회가 되고, 각 항목은 3상태(지시 없음 / 줄이기 / 기동값으로 되돌리기)로 고릅니다 — "지시 없음" 항목은 저장에 실리지 않아, 한 항목만 바꿔도 나머지가 의도치 않게 확정되지 않습니다. 항목마다 줄이는 방향이 따로 적혀 있고(진입점 없는 흐름 차단은 **켜는 쪽**이 줄이는 방향), 영구 아님·전파 지연·철회 비복귀 안내가 화면에 함께 실립니다. 저장된 지시가 없는 서비스는 빈 상태(정상)로 열려 바로 첫 저장이 가능합니다.
- **`-D` 옵션 문자열 생성기** — 원격 설정 화면과 같은 자리에서, 화면으로 시험해 확정한 조합을 JVM `-D` 옵션 한 줄로 만들어 복사합니다(원격 값은 메모리에만 있으므로, 재시작 후에도 유지하려면 `-D` 로 영구화). 기본값과 다른 것만 출력하고, MyBatis 전량 제외(`org.apache.ibatis.binding.MapperProxy`) 선택지와 트레이드오프 안내를 함께 보여 줍니다. Setup 마법사의 옵션 생성과는 별개 표면입니다.
- **에러 기록에 stack trace 본문 채움 (`exception.stacktrace`)** — 에러 span 의 속성에 원인 사슬(`Caused by:`)을 포함한 stack trace 가 담깁니다(4,096자 후미 절단 + `... (truncated)`). 화면의 에러 박스가 유형·메시지에 더해 발생 지점 스택을 보여 줄 수 있게 됩니다.
- **유지보수 상태 조회에 적재 경합 카운터 노출** — `GET /v1/maintenance/status` 응답에 `sqliteBusyEncountered`(경합 횟수)·`sqliteBusyDropped`(유실 청크 수)가 추가됐습니다(기존 두 필드 불변). 카운터는 메모리에만 있어 재시작 시 0 으로 돌아가는 것이 정상이며, 누적 기준선은 `logs/apilens.log` 로 비교합니다. 설정 페이지 "데이터 관리" 섹션에서도 두 값을 확인할 수 있습니다(0 = 정상값).

### Changed

- **적재 202 응답 계약을 "추가만 허용" 으로 재정의** — 기존 두 필드(`accepted`·`traces`)의 이름·타입·의미는 불변이고, `instrumentConfig` 필드가 설정 있는 서비스에만 추가로 실립니다(없으면 필드 자체 생략 — 구 agent·기존 소비자 영향 0).
- **서비스 삭제 시 저장된 원격 계측 설정도 함께 삭제** — 서비스를 지우면 그 서비스의 원격 계측 설정 지시도 같은 트랜잭션에서 함께 철회됩니다(trace/span/payload 데이터는 여전히 보존). 철회 의미론 그대로 — agent 에 이미 적용된 값은 되돌아가지 않습니다.
- **첫 화면 로딩 파일 분할** — UI 를 화면 단위로 나눠 첫 로딩에 내려오는 파일을 줄였습니다. 무거운 차트·그래프 라이브러리는 해당 화면을 열 때 내려옵니다.
- **agent 버전 라벨 정직화** — Services 화면의 agent 버전이 "마지막 확인 시점의 버전" 임을 라벨과 설명으로 명시합니다. collector 가 꺼져 있는 동안 agent 가 시작하면 이전 값이 남아 있을 수 있어, 지금 버전이라고 단정하지 않습니다.
- **설정 API 오류 응답 정합** — 원격 계측 설정 API 에 깨진 JSON 본문을 보내면 이제 다른 오류와 같은 flat `{ "error": ... }` 형식의 400 으로 응답합니다.
- **운영 관측 로그 소폭 보강** — 인증 실패(401) 거절과 적재 202 응답의 설정 동봉 실패가 debug 로그로 관측됩니다(동작 불변 — 로그만).
- **보관 정리(cleanup/purge)의 배치 사이 양보** — 배치 트랜잭션 사이에 짧은 정지(50ms)를 넣어, 정리가 도는 동안에도 적재·조회가 끼어들 틈을 보장합니다(삭제 순서·정확성 불변).
- **server 로그 위생** — 기본 로그 레벨을 DEBUG → INFO 로 조정하고, HikariCP 의 'Retrograde clock change' 반복 경고를 메시지 조건으로만 걸러냅니다(같은 로거의 'Thread starvation' 등 다른 신호는 그대로 보존). 파일 로깅(`logs/apilens.log`)은 그대로 유지됩니다.
- **계측 분석 작업 스레드 상한 고정** — 분석 작업 스레드 풀을 상한 2로 고정했습니다(동시 실행 1건 + 시간 초과로 버려진 작업 1건 구조와 정합).
- **버전 0.5.0 → 0.6.0** (server jar / agent 버전 라벨 / UI 표시 라벨). agent 버전도 0.6.0 으로 정렬됩니다.
- 계측 분석 화면의 MyBatis mapper 안내 문구가 원격 계측 설정 대안을 함께 안내하도록 갱신됐습니다.

### 스키마

- `service_instrument_configs` 테이블 신설(서비스별 원격 계측 설정 저장 — 마이그레이션 V5). 기존 테이블 무변경.

## [0.5.0] - 2026-07-30

> **server + UI 라운드. agent 소스는 한 줄도 바뀌지 않았습니다.** (1) Services 화면에 **agent 버전 컬럼** 추가(스키마 V4) + (2) **계측 분석 화면** 신설 — 어느 클래스가 기록을 많이 만드는지 세 가지 잣대로 보여 주고, 빼기 전에 결과를 미리 계산해 줍니다. **agent jar 재배포가 필요 없습니다** — 기존 agent 를 그대로 두고 collector 만 올리면 됩니다.

### BREAKING CHANGES

_이 release 에는 하위호환을 깨는 변경이 없습니다._ `POST /v1/spans` 적재 계약과 기존 조회 API 응답이 그대로이고, `GET /v1/services` 에 추가된 `agentVersion` 은 필드가 늘어난 것뿐이라 기존 소비자에 영향이 없습니다. 스키마 변경은 컬럼 하나를 더하는 것뿐이며 기존 행은 그 값이 비어 있는 상태로 정상 동작합니다.

⚠️ 다만 **DB 마이그레이션은 되돌릴 수 없습니다.** 0.5.0 으로 한 번 올린 DB 파일을 0.4.0 collector 로 다시 열지 마세요. 올리기 전 DB 파일 백업을 권합니다.

### Added

- **Services 화면 agent 버전 컬럼** — 각 서비스가 어떤 버전의 agent 를 쓰는지 목록에서 바로 보입니다. 값은 agent 가 시작할 때 보내는 첫 기록에서 가져오며, **한 번 받은 값은 계속 유지**됩니다.
  - ⚠️ **이미 돌고 있는 서비스는 재시작 전까지 `—` 로 보입니다.** 지난 값을 거슬러 채우지 않습니다 — agent 를 재시작해야 그때 도착하는 첫 기록으로 채워집니다.
- **계측 분석 화면** (Services 목록의 `계측 분석` 버튼) — 서비스별로 다음을 보여 줍니다.
  - **세 가지 잣대의 순위**: 기록 수 · 본문 건수 · 본문 용량. **잣대마다 순위가 다릅니다** — 기록 수로는 뒤쪽인데 용량으로는 앞쪽인 클래스가 실제로 존재하므로, 한 가지만 보고 정하면 큰 덩어리를 놓칩니다.
  - **빼기 전 예상 결과**: 선택한 클래스를 계측에서 뺐을 때 얼마나 줄어드는지, 그리고 **흐름이 얼마나 조각나는지**를 함께 계산합니다.
  - **조각남 경고**: 조각난 흐름의 비율이 높아지면 경고가 뜹니다. 진행을 막지는 않고 확인 절차가 한 단계 추가됩니다.
  - **뺄 수 있는지 여부 표시**: 화면에 보이는 이름으로 실제로 제외가 되는지를 `가능 / 불가 / 확인 필요` 로 나눠 보여 줍니다. MyBatis mapper 처럼 이름이 달라 제외가 안 되는 계층이 있습니다.
- 분석용 조회 API 2종 — `POST /v1/instrument/analysis`, `POST /v1/instrument/simulation`. 둘 다 읽기 전용이며 기존 인증 정책이 그대로 적용됩니다.

### Changed

- `GET /v1/services` 응답에 `agentVersion` 필드 추가(값이 없으면 `null`).
- Services 표가 좁은 화면에서 잘리지 않도록 가로 스크롤로 바뀌었습니다. **컬럼을 숨기지 않습니다** — 값이 비어 있는 것 자체가 정보이기 때문입니다.
- `docs/agent-options.md` 에 **계측 제외 옵션의 한계**를 명문화했습니다(MyBatis mapper 는 화면 이름으로 제외 불가 / 조상을 빼면 말단이 새 시작점이 되어 흐름이 조각남).

### Fixed

- 서비스 목록 조회에서 마지막 기록 시각이 특정 조건에 드물게 오류를 일으키던 문제를 고쳤습니다.

### Notes

- **agent 버전은 0.4.0 그대로입니다.** 제품 버전(0.5.0)과 다른 것은 어긋남이 아니라 **의도한 결정**입니다 — 이번 라운드는 agent 소스를 바꾸지 않았으므로 agent 가 보고하는 버전도 그대로 두었습니다.
- 분석·예상 계산은 **요청할 때만** 실행됩니다(자동 새로고침 없음). 한 번에 하나씩만 돌며, 오래 걸리면 스스로 멈추고 구간을 좁히라고 안내합니다.

## [0.4.0] - 2026-07-16

> v0.1 이후 **첫 agent·common 모듈 변경 라운드**. (1) agent 계측량 제어 opt-in 옵션(`apilens.instrument.exclude-packages`) + (2) 공유 마스킹 엔진 ReDoS 실행 deadline 3경로 방어(이전 릴리스에 남아 있던 무인증 적재·미리보기 경로의 취약 구멍 종결) + (3) CI relocate 검증 FAIL 게이트 승급. **server API·DB 스키마 무변경(하위호환)**. 다만 v0.1 이후 처음으로 agent jar 산출물이 바뀌므로, 운영망(NAS 등)에 **새 agent jar 재배포 + JVM 재시작이 필요**합니다.

### BREAKING CHANGES

_이 release 에는 하위호환을 깨는 변경이 없습니다._ 관리·조회 API 계약, `POST /v1/spans` 적재 계약, DB 스키마가 모두 그대로이고, 새 계측 옵션은 기본값이 "제외 없음(현재 계측 유지)"인 opt-in 이라 켜지 않으면 동작이 이전과 완전히 같습니다. 구 agent jar 가 새 옵션을 만나도 조용히 무시할 뿐 깨지지 않습니다(안전 폴백).

- **운영 영향 (하위호환이지만 조치 필요)** — v0.1 이후 처음으로 **agent jar 산출물 자체가 바뀝니다**(계측 옵션 추가 + 공유 마스킹 엔진 `apilens-common` 승격에 따른 agent 재빌드 + agent 버전 0.4.0 정렬). 이번 변경(새 계측 옵션·버전 라벨)을 반영하려면 운영망(NAS `vams-prod` 등)에 **새 agent jar 를 다시 배포하고 대상 앱 JVM 을 재시작**해야 합니다. 재배포하지 않아도 구 agent 는 기존대로 동작하지만(무파손), 새 계측 제외 옵션은 새 jar 에서만 유효합니다.

### Added

- **agent 계측 제외 패키지 옵션 (`apilens.instrument.exclude-packages`)** — 운영자가 잡음이 많은 leaf 패키지(예: 특정 batch/repository)를 계측에서 뺄 수 있습니다. 콤마로 패키지 prefix 를 나열하면 그 클래스는 weaving 대상에서 제외돼 span·payload 가 생성되지 않습니다(weaving 시점 결정 — 런타임 비용 0). 기본값은 제외 없음(현재 계측 그대로)이라 켜지 않으면 동작이 이전과 완전히 같습니다. 설치 명령 생성기에는 노출하지 않는 고급 opt-in 으로, NAS 등 운영망 JVM 의 `-D` 로 직접 지정합니다. 실제 저장 부담·대시보드 잡음이 얼마나 줄었는지는 본인 운영망에서 적용 전·후로 측정해 확인하세요(문서가 정량 수치를 단정하지 않습니다).

### Changed

- **agent 버전 0.4.0 정렬** — agent 가 v0.1 이후 처음 변경되는 라운드라, 단일 jar 제품 버전(0.4.0)에 맞춰 agent 버전(`AgentMain.AGENT_VERSION` `"0.1.0"` → `"0.4.0"`)을 올렸습니다. 이번 변경을 반영하려면 NAS 등 운영망에 agent jar 를 다시 배포하고 JVM 을 재시작해야 합니다(구 jar 가 남아 있으면 새 옵션은 조용히 무시되지만, 기본값이 현재 계측 유지라 동작이 깨지지는 않습니다).
- **CI relocate 검증 FAIL 게이트 승급** — agent shadow jar 의 의존성 relocate(사용자 앱과 클래스 충돌 방지)가 깨지면 CI 가 경고만 내던 것을 **빌드 실패(FAIL)** 로 올리고, `javap -v` 로 public API 표면에 raw(비-relocate) 패키지 노출이 0 인지 직접 검증하는 step 을 추가했습니다. 첫 agent 변경 라운드에 맞춰 relocate 회귀를 CI 에서 자동 차단합니다(로컬 실측 raw 0 / shaded 1254).

### Security

- **공유 마스킹 엔진 ReDoS 실행 deadline (3경로 방어)** — 인증 없이 들어온 임의 payload 와 사용자 저장 정규식 룰이 만나 발생할 수 있는 catastrophic backtracking(정규식 폭주)을 실행 시점에 시간 상한으로 막습니다. 마스킹 1회 처리에 기본 1초의 누적 예산을 두고, 초과하면 적재 경로는 해당 payload 를 통째로 마스킹(`***`)한 보수적 형태로 저장한 뒤 계속 진행하며(부분 평문 노출 0), 라이브 프리뷰는 400 으로 응답합니다. 룰 저장 시점의 복잡도 검사(기존)와 함께 저장·프리뷰·적재 세 경로를 공유 엔진 위에서 방어합니다. 이 방어 클래스(`DeadlineCharSequence`·`RegexTimeoutException`)가 `apilens-common` 공유 엔진으로 승격되면서 agent 재빌드가 동반됩니다. 이로써 v0.3.0 이후 남아 있던 **무인증 ingest·프리뷰 경로 ReDoS 미가드 P0 갭을 종결**했습니다.

### Notes

- **agent 재배포 필요** — 위 "운영 영향" 참고. v0.1 이후 첫 agent 산출물 변경이라, 새 계측 옵션·버전 라벨을 반영하려면 새 agent jar 재배포 + JVM 재시작이 필요합니다. 미재배포 시 구 agent 로 기존 동작 유지(안전 폴백).
- **server 업그레이드/롤백** — collector(server) jar 를 0.4.0 으로 교체하고 재기동하면 됩니다. **DB 스키마 변경 0** — 0.3.3 DB 와 그대로 호환되고, 0.3.3 으로 롤백해도 데이터 영향이 없습니다(단, 롤백 시 구 agent jar 도 함께 되돌리는 것을 권장).
- **정량 수치 미주장** — 계측 제외로 저장 부담·잡음이 얼마나 줄었는지, ReDoS 방어가 유실을 얼마나 막았는지의 정량 수치는 단정하지 않습니다. 본인 운영망에서 적용 전·후로 측정해 확인하세요.

### 미구현 (이번 라운드 평가 후 사유 명문)

- **계측 include 필터** — 지정 패키지만 계측하는 대응 레버. 운영자의 축소 레버는 "빼기(exclude)"라 이번엔 exclude 만 구현. include 는 후보로 이관.
- **계측 2차 레버** — 최소 duration 필터(짧은 span drop)·INTERNAL/payload-off 토글. 이번 라운드 범위는 패키지 exclude 필터 하나로 한정.
- **agent 버전 빌드 타임 주입** — 현재 agent 버전은 소스 리터럴이라 수동 bump. 빌드 시 자동 주입(server build-info 접근 불가라 별도 리소스 생성 방식 필요)은 후보로 이관.
- **직렬화 방어 게이트 2종(ResourceRegion·MultipartFile MixIn / FAIL_ON_EMPTY_BEANS)** — 코드 실측 결과 기존 pre-skip / host-throw-0 방어로 이미 목적이 달성돼 중복·marginal 로 판정, 미구현.

## [0.3.3] - 2026-07-10

> 거대 trace 적재 즉시완화(청크 커밋) + OpenAPI 문서 다듬기. **server 전용 — agent·common 모듈은 변경 없음** (v0.1~v0.3.2 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가). breaking change 없음.

### BREAKING CHANGES

_이 release 에는 breaking change 가 없습니다._ server 전용 변경이며 DB 스키마·응답 계약·agent 호환성이 모두 그대로입니다.

### Changed

- **거대 trace 의 적재 write 잠금 독점 완화 (청크 커밋)** — 한 trace 가 수만 개의 span 을 만들면 단일 트랜잭션의 INSERT 가 SQLite write 잠금을 오래 붙잡아, 그 사이 들어온 다른 적재가 `SQLITE_BUSY` 로 유실되던 문제를 완화했습니다. 이제 한 trace 의 span 을 500개 단위 청크로 나눠 청크마다 짧은 트랜잭션으로 커밋합니다. 청크 경계마다 잠금이 풀려 조회·다른 적재가 끼어들 틈이 생기고, 물리적으로 잠금 보유 시간이 줄어듭니다. **이번 릴리스는 완화와 함께 유실 기준선(로그 파일 + `SQLITE_BUSY` 카운터)을 확보하는 라운드입니다** — 유실이 몇 % 줄었다는 정량 수치는 주장하지 않습니다. 근본 해소(agent 과잉 계측 축소)는 다음 agent 라운드입니다.
- **SQLite·커넥션 풀 운영값 조정** — `busy_timeout` 5초 → 10초, HikariCP `maximum-pool-size` 를 4로 명시(SQLite 는 writer 가 하나라 write 는 어차피 직렬이며 WAL 로 조회는 막히지 않습니다), `wal_autocheckpoint` 를 1000 → 10000 pages(약 40MB, connection-init-sql 로 적용)로 넓혔습니다. 청크 커밋(근본 레버) 뒤의 보조 헤드룸이며 실측으로 튜닝할 수 있습니다.
- **`SQLITE_BUSY` 발생·유실 카운터 + 로그 파일화** — 적재 경합으로 `SQLITE_BUSY` 를 만난 횟수와 그로 인해 버린 청크 수를 세어 WARN 로그로 남깁니다. 로그를 stdout 전용에서 `logs/apilens.log` 파일로도 남겨(기본 롤링), 완화 전·후 유실 기준선을 로그 grep 으로 비교할 수 있습니다. 카운터는 메모리에만 두므로 재시작하면 0 으로 돌아갑니다(스키마 변경 0).
- **payload 재적재 멱등화** — 같은 span 이 다시 들어오면 payload 를 지우고 다시 넣어(delete-then-insert) 중복이 쌓이지 않습니다. 처음 저장되는 span 은 동작이 이전과 같고(순개선), 부분 적재 뒤 재수신 경로에서만 "중복 → 대체" 로 바뀝니다.
- **payload 조회 API 문서 문구 보강** — payload 조회(`/v1/traces/{traceId}/spans/{spanId}/payloads`) 설명에 "본문은 저장 시 이미 마스킹이 1회 적용된 결과이며 재마스킹은 없다"는 뉘앙스를 더했습니다.

### Added

- **API 오류 응답 공통 스키마** — OpenAPI 문서에 공통 오류 응답(`ErrorResponse`, `{ "error": "..." }` 형태) 스키마를 한 곳에 정의해 노출합니다. 각 endpoint 의 오류 응답 형태를 문서에서 일관되게 확인할 수 있습니다.
- **유지보수 API 6종 문서 설명** — `/v1/maintenance/*` 6개(cleanup·purge·optimize·status·pause·resume)에 동작 설명(`@Operation`)을 붙여 `/swagger-ui` 에서 각 동작의 부작용·주의사항을 바로 볼 수 있습니다.
- **API 문서(Swagger) 노출 안내** — 내부 운영망(예: NAS agent → 맥 collector)이면 추가 조치가 필요 없고, ApiLens 를 인터넷에 직접 노출하는 배포라면 `/swagger-ui`·`/v3/api-docs` 를 리버스 프록시/인증 앞단에서 막으라는 안내를 `docs/setup.md` 에 추가했습니다.

### Notes

- **agent·common 모듈 무변경** — agent jar 재빌드가 필요 없습니다. collector(server) jar 만 0.3.3 으로 교체하고 재기동하면 됩니다.
- **업그레이드/롤백** — 0.3.2 jar 와 DB 가 그대로 호환됩니다 (스키마 미변경). 0.3.2 로 롤백해도 데이터 영향 0.
- **운영 관찰** — `logs/apilens.log` 의 `SQLITE_BUSY` 카운터/WARN 로그로 완화 전·후 유실 기준선을 비교할 수 있습니다.

## [0.3.2] - 2026-07-10

> API 문서 자동화 (OpenAPI / Swagger UI). server 와 UI 버전 라벨만 바뀌고 **agent·common 모듈은 변경 없음** (v0.1~v0.3.1 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가).

### Added

- **API 문서 자동화 (OpenAPI / Swagger UI)** — 손으로 쓰던 마크다운 API 문서가 코드와 어긋나는 문제를 근본 해소하기 위해, springdoc-openapi 로 컨트롤러에서 스펙을 자동 생성하고 인터랙티브 문서를 단일 jar 에 임베드했습니다. server 를 띄운 뒤 `/swagger-ui` (인터랙티브 문서) 또는 `/v3/api-docs` (OpenAPI JSON) 를 열면 최신 요청·응답 스키마·상태 코드를 그대로 확인·시험 호출할 수 있습니다. server 전용 의존성이라 **agent 는 무관** (재빌드 불필요).
- **API 문서 경로는 인증 없이 열립니다** — API Key 를 설정한 기동 상태에서도 `/swagger-ui` · `/v3/api-docs` 는 토큰 없이 접근할 수 있습니다 (의도된 면제, 기존 `/v1/**` 관리·조회 API 보호는 그대로 유지). 문서 경로를 위한 신규 필터·인터셉터 추가는 없습니다 (기존 인증 화이트리스트의 default-deny 역방향 기본값으로 자동 면제).
- **문서 버전이 빌드 버전을 자동 추종** — OpenAPI `info.version` 을 손으로 적지 않고 빌드 버전(build-info)에서 주입해, 버전을 올릴 때 문서 버전이 어긋나는 stale 을 원천 차단했습니다.

### Changed

- **`docs/api.md` 를 운영 서사 보조 문서로 축소** — 필드 단위 요청/응답 계약은 자동 스펙(`/swagger-ui`) 단일 진실 출처로 일원화하고, 손 문서에는 자동 스펙이 담기 어려운 운영 서사(유지보수 503 부작용·마스킹 적용 시점·인증 헤더 전제·디스크 회수 한계·프리뷰 신뢰 도구)만 남겼습니다. 코드와 손 문서가 어긋나는 stale 을 줄입니다.
- **버전 0.3.1 → 0.3.2** (server jar / UI `package.json` / UI 표시 라벨).

### Notes

- **agent·common 모듈 무변경** — agent jar 재빌드가 필요 없습니다. jar 만 0.3.2 로 교체하고 재기동하면 됩니다.
- **업그레이드/롤백** — 0.3.1 jar 와 DB 가 그대로 호환됩니다 (스키마 미변경). 0.3.1 로 롤백해도 데이터 영향 0.

## [0.3.1] - 2026-06-23

> 유지보수 모드(수신 일시정지). 디스크 최적화(VACUUM)·정리를 잠금 경합 없이 끝내도록 **collector 의 적재만 잠시 멈추는** 기능입니다. server 와 UI 만 바뀌고 **agent·common 모듈은 변경 없음** (v0.1~v0.3.0 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가 — 상태는 in-memory).

### Added

- **유지보수 모드 (수신 일시정지)** — 설정 페이지 "데이터 관리" 섹션의 [수신 일시정지]/[수신 재개] 버튼으로 collector 의 적재를 잠시 끊습니다. 운영 중인 서비스는 멈추지 않고 collector 쪽 수신만 일시 차단해, 디스크 최적화(VACUUM)·정리(purge/cleanup)를 SQLite write 잠금 경합 없이 안전하게 수행합니다.
- **수신 일시정지 API 3종** (`io.apilens.server.retention.MaintenanceController`) — `GET /v1/maintenance/status`(상태 조회) · `POST /v1/maintenance/pause`(일시정지) · `POST /v1/maintenance/resume`(재개). 모두 `MaintenanceStatusResponse { paused, pausedAt }` 를 echo 하고 **멱등**합니다. `/v1/**` default-deny 로 보호됩니다 (v0.3.0 의 API Key 인증 자동 계승 — 키 설정 시 토큰 필수).
- **max-pause cap (30분 자동 재개)** — 운영자가 일시정지를 켜둔 채 잊어도 30분 뒤 server 가 자동으로 수신을 재개합니다 (안전장치). 별도 스케줄러 없이 요청 시점 lazy 판정으로 동작합니다.
- **유지보수 모드 UI** — 화면 상단 고정 배너("수신 일시정지 중 — 이 동안 들어온 데이터는 저장되지 않습니다") · Services 화면 배지 · 일시정지 중 대시보드 실시간 갱신 자동 중단. 정리(최적화·삭제) 전 수신 일시정지를 권장하는 안내 텍스트 (강제 아님).

### Changed

- **`POST /v1/spans` ingest 응답** — 유지보수 모드(수신 일시정지) 중에는 `503 Service Unavailable` + `Retry-After: 60` 헤더로 응답하고, validate/mask/DB write 를 전부 건너뜁니다. 일시정지 중 들어온 trace 는 **저장되지 않습니다** (agent 는 해당 batch 를 drop). 정상 수신 시 응답은 기존과 동일(`202` + `{ accepted, traces }`).

### Notes

- **상태는 in-memory 입니다** — server 를 재시작하면 항상 수신 중(`paused=false`)으로 복귀합니다 (DB 에 저장하지 않음, 스키마 변경 0).
- **agent·common 모듈 무변경** — agent jar 재빌드가 필요 없습니다. jar 만 0.3.1 로 교체하고 재기동하면 됩니다.
- **업그레이드/롤백** — 0.3.0 jar 와 DB 가 그대로 호환됩니다 (스키마 미변경). 0.3.0 으로 롤백해도 데이터 영향 0.

## [0.3.0] - 2026-06-22

> v0.3 첫 릴리스. **API Key 인증 + 마스킹 정규식 ReDoS 가드 + 온라인 디스크 최적화(VACUUM)**. server 와 UI 만 바뀌고 **agent 모듈은 변경 없음** (v0.1/v0.2 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가).

### BREAKING CHANGES

- **관리/조회 API 에 선택적 API Key 인증이 신설됐습니다.** `APILENS_AUTH_API_KEY` 환경변수(또는 `-Dapilens.auth.api-key` 시스템 프로퍼티)로 토큰을 설정하면, 그 시점부터 settings / masking-rules / maintenance / traces / services 등 `/v1/**` 관리·조회 API 는 `Authorization: Bearer <토큰>` 헤더를 요구합니다. 헤더가 없거나 틀리면 401 `{"error":"unauthorized"}` 를 반환합니다.
  - **업그레이드 경로**: jar 만 0.3.0 으로 교체하고 재기동하면 됩니다 (스키마·agent 무변경). **토큰을 설정하지 않으면 0.2.x 와 똑같이 무인증으로 동작**하고 기동 시 경고 로그를 1회 남깁니다 — 기존 환경이 그대로 깨지지 않으니, 준비되면 토큰을 켜세요.
  - **롤백**: 0.2.x jar 로 되돌려도 DB 가 그대로 호환됩니다 (이 릴리스는 스키마를 바꾸지 않습니다).
  - **agent(ingest) 영향 0**: agent 가 trace 를 보내는 `POST /v1/spans` 는 **인증에서 제외**됩니다. 토큰을 켜도 NAS 등 원격 agent 의 적재는 끊기지 않습니다 (신뢰 네트워크 전제).
  - **사용 예시**:
    ```bash
    # 토큰을 켜서 기동 (관리·조회 API 보호)
    APILENS_AUTH_API_KEY="your-secret-token" java -jar apilens-server-0.3.0.jar

    # API 호출 시 헤더로 토큰 전달
    curl -H "Authorization: Bearer your-secret-token" http://localhost:8765/v1/traces

    # 토큰을 빼면 0.2.x 와 동일 (무인증 + 기동 경고 1회)
    java -jar apilens-server-0.3.0.jar
    ```
    토큰 생성·UI 입력·Docker·키 교체 등 자세한 사용법은 [README 의 '인증 | Authentication' 절](./README.md#인증--authentication-v03)을 참조하세요.

### Added

- **API Key 인증** (`io.apilens.server.auth`) — Spring Security 의존성을 추가하지 않은 경량 서블릿 필터(`OncePerRequestFilter`)로 단일 토큰을 검증합니다. 토큰 비교는 타이밍 공격을 막기 위해 상수 시간(`MessageDigest.isEqual`)으로 수행합니다. **인증 면제(무토큰 허용) 경로**: setup wizard(`/v1/setup/**`) · agent 적재(`POST /v1/spans`) · 헬스체크(`/actuator/health`) · 정적 자산과 SPA 화면(`/`, `/index.html`, `/assets/**`). 그 외 모든 `/v1/**` 는 토큰이 설정돼 있으면 보호됩니다. 토큰은 server 기동 옵션으로만 두며 **DB 에 저장하지 않습니다** (키 교체 = server 재시작).
- **인증 토큰 입력 UI** — 설정 페이지에서 토큰을 입력하면 브라우저 세션(`sessionStorage`)에 보관하고 이후 모든 요청에 헤더로 붙입니다. 토큰이 없거나 틀려 401 이 나면 자동 새로고침(polling)을 멈추고 토큰 입력을 유도합니다 (잘못된 토큰으로 401 이 반복되며 화면이 먹통이 되는 것을 막습니다). 토큰 저장은 브라우저 보관만 하고 어떤 보호 API 도 호출하지 않습니다 (토큰 입력 화면 자체가 인증에 막혀 영구 잠기는 일이 없게).
- **마스킹 정규식 ReDoS 가드** — 사용자가 마스킹 룰(custom 정규식)을 추가/수정할 때, 저장 직전에 제한 시간(100ms) 안에 시험 매칭이 끝나는지 검사합니다. catastrophic backtracking(입력 길이에 지수적으로 느려지는 정규식)이 의심되면 저장을 거부하고 400 `pattern is too complex` 를 반환합니다. 별도 스레드를 띄우지 않고 매칭 도중 경과 시간을 검사해 스스로 빠져나오는 방식이라 스레드 누수가 없습니다.
- **온라인 디스크 최적화(VACUUM)** (`POST /v1/maintenance/optimize`) — 설정 페이지 "데이터 관리" 에 "최적화" 버튼이 추가됐습니다. 데이터를 지우지 않고 SQLite `VACUUM` 으로 파일 내부의 빈 공간(free page)을 회수해 DB 파일 크기를 줄입니다. `cleanup`/`purge` 가 행을 지워도 파일 크기가 잘 안 줄던 한계(삭제된 빈 page 가 파일에 남음)를 보완합니다. **VACUUM 은 운영 DB 파일을 삭제/재생성하지 않습니다** (같은 파일을 내부 재구성). 응답 `{ deletedTraces: 0, freedBytes, dbSizeBytes, busy }`.

### Changed

- **버전 0.2.1 → 0.3.0** (server jar / UI `package.json` / UI 표시 라벨).
- **`MaintenanceResult` 응답에 `busy` 필드 추가** — `cleanup`/`purge`/`optimize` 응답에 `busy`(boolean)가 추가됐습니다. `optimize` 가 라이브 적재와 경합해 잠금을 못 얻으면 예외를 던지지 않고 `busy=true` 로 부분 실패를 알립니다 (`cleanup`/`purge` 는 항상 `false`). 기존 필드(`deletedTraces`/`freedBytes`/`dbSizeBytes`)는 그대로입니다.
- **`application.yml` 에 `apilens.auth.api-key` 설정 추가** (기본 비어 있음 = 무인증 폴백). env/시스템 프로퍼티 주입을 권장합니다.

### Security

- **maintenance·설정·마스킹 룰·조회 API 를 인증으로 보호할 수 있습니다** — v0.2.x 까지 무인증이던 파괴적/상태변경 동작(전체 삭제 `purge`, 설정 변경, 룰 추가/삭제, 서비스 삭제)을 토큰으로 막을 수 있게 됐습니다.
- **온라인 VACUUM 디스크 여유 가드** — `VACUUM` 은 작업 중 원본 크기만큼 임시 파일을 만들어 디스크를 일시적으로 약 2배 점유합니다. 실행 전 가용 디스크가 DB 크기보다 작으면 거부하고, 실행 중 `SQLITE_FULL`/`SQLITE_BUSY` 가 나도 예외를 던지지 않고 상태로만 알립니다 (디스크가 꽉 차는 2차 사고 방지).
- **남은 한계 (모두 신뢰 네트워크 전제의 의도된 제약)**:
  - **agent 적재(`/v1/spans`)는 무인증** — agent 모듈을 바꾸지 않기 위한 선택입니다. 신뢰망 밖에서 접근하면 가짜 trace 주입이 가능하니, 방화벽/망 격리가 유일한 방어입니다.
  - **토큰이 평문(HTTP)으로 전송됩니다** — v0.3 은 TLS 를 내장하지 않습니다. 같은 망의 패킷 스니핑에 토큰이 노출될 수 있으니, 운영망/방화벽 격리 환경에서만 쓰고 필요하면 리버스 프록시(nginx 등)로 TLS 를 종단하세요. 공용망에 직접 노출하지 마세요.
  - **ReDoS 가드는 신규 룰 저장 시점만 검사** — 이미 저장된 룰이나, 무인증 적재(`/v1/spans`)로 들어온 payload 가 마스킹을 거치는 핫패스는 가드 대상이 아닙니다. 근본 차단(공유 마스킹 엔진의 linear-time 매칭)은 agent 와 함께 바꿔야 해 다음 agent 라운드로 미뤘습니다.

## [0.2.1] - 2026-06-18

> server-only 유지보수 릴리스. **agent 모듈은 변경 없음** (v0.1/v0.2 agent 그대로 호환). 스키마 변경 0 (마이그레이션 미추가).

### Added

- **payload 크기 가드 (server-side)** — ingest 저장 직전, 개별 payload body 가 `apilens.ingest.max-payload-bytes`(기본 1MB)를 넘으면 server 가 한도까지 잘라 저장하고 `truncated=true` 로 기록합니다. agent 가 정상 흐름에서 먼저 64KB 로 자르므로 보통은 동작하지 않는 안전망이며, agent 우회·오작동·대형 payload 폭증을 server 저장 시점에 차단합니다. 마스킹 적용 후 측정·절단하므로(mask → truncate) 마스킹을 우회하지 않고, UTF-8 문자 경계를 보존해 멀티바이트 문자를 쪼개지 않습니다.
- **수동 데이터 정리 API** (`POST /v1/maintenance/cleanup` · `POST /v1/maintenance/purge`) — 설정 페이지 "데이터 관리"에서 보관 기간 즉시 적용 / 전체 삭제를 트리거합니다. 두 동작 모두 `payloads → spans → traces` 순 행 단위 배치 DELETE + PRAGMA 로만 공간을 회수하며 **DB 파일을 삭제/이동/재생성하지 않습니다**. purge 는 되돌릴 수 없으며, 이 버전 시점에는 **인증이 없으므로** server 를 신뢰 네트워크에만 노출해야 합니다. 응답은 `{ deletedTraces, freedBytes, dbSizeBytes }`.

### Changed

- **ingest 설정 키 이름 교정** — `apilens.ingest.max-batch-size-bytes` → `apilens.ingest.max-payload-bytes`. 옛 키는 "batch 총합 거부"를 암시했지만 v0.1 부터 읽는 코드가 없는 dead key 였고, 실제 정책은 위 payload 가드의 **개별 payload body 한도**입니다. yml override 에 옛 키를 적어 둔 환경은 새 키로 바꿔 주세요 (안 바꿔도 기본 1MB 로 동작).
- **수동 정리 시 WAL 회수 보강 + 한계 명문화** — `cleanup`/`purge` 후 `incremental_vacuum → wal_checkpoint(TRUNCATE) → ANALYZE` 순서로 `-wal` 파일을 0바이트로 잘라 디스크를 회수합니다. 다만 `incremental_vacuum` 은 한 번 부를 때 한 페이지씩만 진행되고, `wal_checkpoint` 는 화면을 보는 중(reader 활성) 실행 시 busy 로 부분 실패할 수 있어(다음 정리에서 재시도) `freedBytes` 가 삭제량 대비 작게 나올 수 있습니다 (한도이지 결함 아님). (당시 "파일 끝(tail)의 free page 만 회수한다" 고 적었던 사유는 잘못된 설명이었습니다 — 0.6.1 에서 정정했습니다.)

## [0.2.0] - 2026-06-11

> 성능 수습 + 설정 페이지 + trace 필터. agent 모듈은 변경 없음 (v0.1 agent 그대로 호환).

### BREAKING CHANGES

- **`GET /v1/services` 의 `traceCount` 의미 변경 — 누적 전수 → 최근 24시간** (`start_time` 기준, 경계값 포함). 누적 카운트가 대용량 DB 에서 매 호출 풀스캔을 유발해 윈도우 한정으로 변경했습니다. 필드명·응답 구조는 무변경이며 UI 라벨은 `Trace 수 (24h)` 로 표기합니다. 이 값을 누적 카운트로 소비하던 외부 스크립트가 있다면 의미를 재해석해야 합니다.
- **retention cleanup 이 실제로 동작 시작 — 기본 30일 초과 trace 자동 영구 삭제**. v0.1 의 retention 설정은 읽는 코드가 없는 dead key 여서 데이터가 무한 보관됐지만, v0.2.0 부터 매일 04:00 에 보관 기간(기본 30일)을 초과한 trace 가 영구 삭제됩니다 (복구 불가). 30일 넘은 trace 를 보존하려면 업그레이드 후 첫 04:00 전에 설정 페이지(`/settings`)에서 보관 기간을 늘려 두세요 (1~3650일).
- **SQLite `journal_mode` 가 `delete` → `WAL` 로 전환** — DB 파일 옆에 `-wal` / `-shm` 동반 파일이 생기며, 한 번 WAL 로 전환된 DB 는 v0.1 jar 로 롤백해도 WAL 이 유지됩니다 (v0.1 동작에 문제는 없음). 또한 `auto_vacuum=INCREMENTAL` 전환을 위해 v0.2.0 첫 기동 시 1회 `VACUUM` 이 수행됩니다 — 기존 DB 크기에 비례한 1회성 기동 지연이 있습니다 (멱등 — 2회차 기동부터 건너뜀).

### Added

- **설정 페이지 (`/settings`)** — 보관 기간(retention) 변경 + 마스킹 룰 관리 UI.
- **마스킹 룰 관리 API** (`/v1/masking-rules` — 목록/추가/삭제/토글 + 라이브 프리뷰). v0.1 의 빌트인 default 룰 4종(주민번호/카드번호/password/token)은 그대로이며, 관리 동작이 추가된 것입니다. default 룰은 **삭제 불가(409), 비활성 토글만 가능**. 프리뷰는 agent/server 와 같은 공유 마스킹 엔진으로 계산 — 화면 토글(저장 전) 상태를 그대로 반영합니다.
- **룰 핫 리로드** — 룰 추가/삭제/토글이 서버 재기동 없이 적용됩니다. **룰 변경은 이후 ingest 분에만 적용 — 기존 저장 payload 의 재마스킹은 없습니다** (재마스킹 경로 자체가 없음). 룰 로드 실패 시 동작: 기동 시점 실패는 기동을 차단하고 (마스킹 없는 침묵 기동 방지 — v0.1 의 fail-closed 와 동일한 의미), 운영 중 reload 실패는 기존 룰 세트를 그대로 유지합니다.
- **설정 API** (`GET/PUT /v1/settings`) — `retention.days` (정수 1~3650, 원자 적용). **설정 페이지 저장 값(DB)이 yml 값보다 우선**, 미설정 시 yml fallback (기본 30일).
- **retention cleanup 스케줄러** — 매일 04:00 (yml `apilens.retention.cleanup-cron`), payloads → spans → traces 순 행 단위 배치 삭제 (DB 파일 삭제/재생성 없음).
- **Dashboard trace 필터** — status + operation 검색 (`GET /v1/traces?q=` — root operation 풀 FQCN 부분 일치, `%`/`_`/`\` 리터럴 매칭).

### Changed

- **ingest 저장을 batchUpdate 로 전환** — span/payload 단건 INSERT 루프 제거 (수신 batch 당 왕복 감소). 저장 결과·REPLACE 동작은 동일.
- **SQLite 운영 설정 적용** — `journal_mode=WAL` (위 BREAKING CHANGES 참조) / `synchronous=NORMAL` / `busy_timeout=5000` 을 JDBC URL 로 모든 connection 에 적용. `auto_vacuum=INCREMENTAL` 전환은 첫 기동 시 1회 자동 수행 (기존 v0.1 DB 포함, 멱등 — sqlite-jdbc 가 auto_vacuum 을 URL 파라미터로 지원하지 않아 startup 전환 방식 채택). 조회 인덱스 1종 추가 (`traces(service_name, start_time DESC, trace_id DESC)`).

## [0.1.0] - 2026-06-03

> 첫 public release. 한국 SI 운영자가 NAS 같은 환경에 결재 없이 깔 수 있는 가벼운 호출 추적 도구.
>
> **사전 dogfooding 으로 `0.1.0-SNAPSHOT` 빌드를 배포한 환경**(예: NAS 원격)은 아래 **Changed** 의 wizard 옵션 키 변경과 **Security** 의 데이터 무결성 fix 때문에 **즉시 `0.1.0` 으로 재배포** 하기를 권장합니다.

### Added

- **Spring Boot agent** (ByteBuddy + premain, shadow jar relocate). 호스트 앱과 클래스 충돌 0 — 모든 외부 의존성을 `io.apilens.agent.shaded.*` 로 relocate.
- **HTTP server in/out 호출 instrument** (Spring MVC + WebClient / RestTemplate).
- **JDBC PreparedStatement instrument** (raw `Statement` 는 best-effort). 표준 setter + `addBatch()` 가 PAYLOAD IN 에 직렬화됩니다.
- **JDBC ResultSet capture** (opt-in `apilens.jdbc.capture-result-set=true`, 최대 100 row / 65536 bytes).
- **MyBatis Mapper instrument**.
- **Span collector** (HTTP POST → `/v1/spans`, daemon thread batch 송신).
- **W3C Trace Context (`traceparent`) 표준 전파** — OpenTelemetry 호환 span 모델.
- **Trace / Span / Payload 분리 저장** (SQLite + Flyway). 큰 payload 는 마스킹 적용 후 별도 테이블에 저장.
- **Server-side PII 마스킹** — 주민등록번호 / 카드번호 / `password`·`token`·`secret` 기본 룰을 ingest 시 서버에서 자동 적용.
- **노드 그래프 UI** (mind-map, 수평 시간 흐름) + 응답시간 대시보드 + payload inspector + 에러 시 stack trace 즉시 표시. React + Vite + TypeScript.
- **Setup wizard** — 4단계로 `-javaagent:` 옵션 한 줄 생성 (java / Maven / Gradle / Docker 환경별 안내, agent jar 자동 추출·다운로드).
- **단일 jar 배포** — server jar 안에 agent jar + UI 정적 파일 임베드.
- **Apache 2.0** 라이선스.

### Changed

> 0.1.0 이 첫 public release 이므로 공개 버전 간 호환성 영향은 없습니다. 아래는 사전 SNAPSHOT 빌드를 배포한 환경에만 해당합니다.

- **Setup wizard 가 출력하는 agent 옵션 키 교정** — `apilens.server.url` → `apilens.server`, `apilens.capture.params` → `apilens.jdbc.capture-params`, `apilens.capture.resultset` → `apilens.jdbc.capture-result-set`. 기존 키는 agent 가 읽지 못해 원격(app ≠ server) 환경에서 trace 0건이 되는 버그가 있었습니다. 옛 옵션을 복붙해 둔 환경은 새 키로 교정 후 재기동이 필요합니다.
- **`GET /v1/services` 응답 필드** — `lastSeen` 이 `lastSeenAt`(nullable) 으로 대체되고 `registeredAt` / `source` / `healthStatus` 가 추가되었습니다.

### Fixed

- **`apilens.jdbc.capture-result-set=true` 시 NULL 값 primitive getter 의 호스트 크래시 차단** — NULL 컬럼에 `getLong` 등을 호출할 때 타입 불일치로 호스트 앱이 기동 크래시하던 문제를, 반환 타입별 정합 wrapper 로 교정.
- **agent 가 server 에 연결하지 못할 때의 반복 에러 로그 억제** — 매 시도마다 출력하던 것을 끊김/복구 전환 시 1회씩만(edge-triggered) 출력하도록 변경.

### Security

- **[CRITICAL] agent 직렬화가 호스트 앱 파일을 0바이트로 truncate 하던 결함 차단** — `ResponseEntity<ResourceRegion>` 같은 반환값을 직렬화하면서 `Resource` 의 stream getter 를 호출해 호스트 파일이 잘리던 문제(실측: 비디오 스트리밍 endpoint 에서 mp4 약 649MB). 위험 타입 사전 차단 + body 타입 검사 + Jackson MixIn 의 3중 방어로 해결.
- **[CRITICAL] `apilens.jdbc.capture-result-set=true` 가 호스트 SELECT 결과를 잘라내고 커넥션 누수를 유발하던 결함 차단** — preread 후 원본을 닫던 설계를 pass-through tee 로 교체. 호스트는 전체 결과를 받고, 캡처 버퍼는 payload 샘플 용도로만 동작합니다 (`truncated=true` 는 payload 샘플 상한일 뿐 호스트 결과 손실이 아닙니다).
- **이전 `0.1.0-SNAPSHOT` 빌드 사용자는 즉시 `0.1.0` 으로 재배포 권장** — 위 두 결함은 agent 가 부착된 호스트 앱의 데이터를 파괴할 수 있었습니다.
- **`apilens.jdbc.capture-params` 기본값 `true`** (default ON) — PreparedStatement 파라미터가 PAYLOAD IN 에 직렬화됩니다. PII 가 우려되면 `apilens.jdbc.capture-params=false` 로 끄세요.

## Known limitations

현재 버전의 한계는 [README 의 "현재 한계 | Current Limitations" 절](./README.md#현재-한계--current-limitations)이 정본입니다.
버전별로 무엇이 언제 해소됐는지는 위 릴리스 블록을 보세요.
