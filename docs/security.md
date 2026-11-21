# 보안 공지 대조표 | Security advisories check (v0.11.2)

ApiLens v0.11.2 가 쓰는 부품에 대해 **공식 페이지에서 직접 확인한** 보안 공지만 싣습니다.
공식 페이지를 열지 못했거나 공지 번호를 찾지 못한 항목은 싣지 않았습니다.

This table lists only advisories that were **checked on the official source page** for the components that
ApiLens v0.11.2 ships with. Advisories that could not be confirmed on an official page are not listed.

- 확인 시각은 표마다 「확인 시각」 칸에 있습니다 — 한 행씩 따로 잰 시각이 아니라 그 묶음을 확인한 시간대(KST)입니다. 이 문서는 그 시각의 공지 내용이고, 그 뒤에 나온 공지는 들어 있지 않습니다.
- 「v0.11.2 판」은 빌드가 실제로 고른 판입니다(`./gradlew dependencies` · npm 잠금 파일).
- 「성립 조건」은 공지가 말하는 조건이고, 「ApiLens」 칸은 그 조건이 이 제품 코드 · 설정에 있는지 확인한 결과입니다.
- 판정: **고침** = 이번 판올림으로 고침판 위에 있습니다 · **해당 없음** = 고침판 위이거나 조건이 이 제품에 없습니다 · **해당 없음(판은 범위 안)** = 판은 공지 범위 안이지만 성립 조건이 이 제품에 없습니다 / in range, but the condition does not apply here.
- 「확인한 목록」: https://spring.io/security · https://tomcat.apache.org/security-11.html · https://www.sqlite.org/cves.html · https://logback.qos.ch/news.html · GitHub 저장소 공지 목록(`FasterXML/jackson-databind` · `FasterXML/jackson-core` · `FasterXML/jackson-dataformats-text` · `FasterXML/jackson-modules-java8` · `FasterXML/jackson-annotations` · `xerial/sqlite-jdbc` · `hibernate/hibernate-orm` · `springdoc/springdoc-openapi`) · https://www.oracle.com/security-alerts/cspunov2026.html · https://openjdk.org/groups/vulnerability/advisories/ — 공지를 하나씩이 아니라 목록 페이지 전체로 훑었습니다 / Checked lists: the pages above, read as whole lists rather than advisory by advisory.

## 서버(collector) — Spring Boot 4.1.3 · Spring Framework 7.0.11 · Tomcat 11.0.27

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.11.2 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인 시각 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|---|
| CVE-2026-47890 | LOW | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.11 | SSE 로 view fragment 를 보냄 | SSE · view fragment 쓰는 코드 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-47890 |
| CVE-2026-47888 | MEDIUM | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.11 | Spring RSocket 사용 | RSocket 의존 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-47888 |
| CVE-2026-59280 | MEDIUM | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.11 | FreeMarker 뷰 이름을 밖의 값으로 만듦 | FreeMarker 의존 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-59280 |
| CVE-2026-41855 | HIGH | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.11 | 믿을 수 없는 JMS 환경 | JMS 의존 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-41855 |
| CVE-2026-41852 | LOW | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.11 | 사용자가 준 SpEL 식을 평가 | SpEL 평가 코드 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-41852 |
| CVE-2026-41842 | HIGH | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.11 | 파일 시스템 정적 자원 + 버전 자원 설정 | 버전 자원 설정 0 | 고침 | 정적 자원 체인을 설정할 때 | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-41842 |
| CVE-2026-22731 | HIGH | Boot 3.4.0–3.4.14 · 3.5.0–3.5.11 · 4.0.0–4.0.3 → 3.5.12 · 4.0.4 | 4.1.3 | Actuator + 사용자 health group 을 `additional-path` 로 본 서버에 노출 | health group · `additional-path` 설정 0 | 해당 없음 | health group 을 더할 때 | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-22731 |
| CVE-2026-22733 | HIGH | Boot 3.4.0–3.4.14 · 3.5.0–3.5.11 · 4.0.0–4.0.3 → 3.5.12 · 4.0.4 | 4.1.3 | Actuator + CloudFoundry Actuator 경로 아래 인증 endpoint | CloudFoundry 설정 0 | 해당 없음 | — | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-22733 |
| CVE-2026-40976 | CRITICAL | Boot 4.0.0–4.0.5 → 4.0.6 | 4.1.3 | Spring Security 기본 필터 체인에 기댐 | Spring Security 의존 0(인증은 자체 API 키 필터) | 해당 없음 | Spring Security 를 들일 때 | 2026-10-28 20:13~21:31 | https://spring.io/security/cve-2026-40976 |
| CVE-2026-101356 | Important | Tomcat 10.1 → 10.1.61 · 11 → 11.0.27 | 11.0.27 | HTTP/2 비동기 처리 | HTTP/2 설정 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-101296 | Important | Tomcat 10.1 → 10.1.61 · 11 → 11.0.27 | 11.0.27 | JNDIRealm + GSSAPI 아닌 SASL | JNDI realm 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://tomcat.apache.org/security-11.html |
| CVE-2024-12798 | MEDIUM | logback-core < 1.5.13 → 1.5.13 | 1.5.38 | 설정 파일을 공격자가 고칠 수 있음 | 설정은 jar 안 | 고침 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-pr98-23f8-jwxv |
| CVE-2024-12801 | LOW | logback-core < 1.5.13 → 1.5.13 | 1.5.38 | 같음 | 같음 | 고침 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-6v67-2wr5-gvf4 |
| CVE-2026-59374 | HIGH | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 데이터 바인딩의 허용 · 금지 필드 설정에 기댐 | 그 설정 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59374 |
| CVE-2026-59389 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | WebFlux 다중 파트 처리 | WebFlux 사용 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59389 |
| CVE-2026-59394 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | `UrlHandlerFilter` 사용 | 그 필터 등록 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59394 |
| CVE-2026-59398 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 질의 매개로 응답 형식을 고르는 설정 | 그 설정 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59398 |
| CVE-2026-59418 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | WebFlux 가 요청 본문을 줄 단위 글자 흐름으로 읽음 | WebFlux 사용 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59418 |
| CVE-2026-59419 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 원격 사용자가 고른 HTTP 방법으로 밖에 요청을 보냄 | 그런 코드 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59419 |
| CVE-2026-59429 | LOW | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 경로 패턴 안 `{이름:정규식}` | 그 패턴 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59429 |
| CVE-2026-59430 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 사용자가 준 SpEL 식을 평가 | SpEL 평가 코드 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59430 |
| CVE-2026-59431 | MEDIUM | Framework 7.0.0–7.0.10 → 7.0.11 | 7.0.11 | 같음 | 같음 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59431 |
| CVE-2026-59378 | HIGH | Boot 4.1.0–4.1.2 → 4.1.3 | 4.1.3 | Jersey + Actuator + Spring Security | Jersey · Spring Security 의존 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59378 |
| CVE-2026-59409 | MEDIUM | Boot 4.1.0–4.1.2 → 4.1.3 | 4.1.3 | Actuator heapdump 노출 | Actuator 노출은 health · info 뿐 | 고침 | Actuator 노출을 넓힐 때 | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59409 |
| CVE-2026-59413 | MEDIUM | Boot 4.1.0–4.1.2 → 4.1.3 | 4.1.3 | Actuator `@Selector` 질의 매개 | 같음 | 고침 | 같음 | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59413 |
| CVE-2026-59372 | LOW | Boot 4.1.0–4.1.2 → 4.1.3 | 4.1.3 | 터키어 · 아제르바이잔어 로캘 + Actuator 값 가리기 | env · configprops 노출 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://spring.io/security/cve-2026-59372 |
| CVE-2026-116600 | Important | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | WebSocket + OWB 모듈 | WebSocket 끝점 · OWB 0 | 해당 없음(판은 범위 안) | Tomcat 11.0.30 이 나오거나 Spring Boot 가 11.0.29 이상을 실을 때 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-116830 | Important | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | HTTP/2 | HTTP/2 설정 0 | 해당 없음(판은 범위 안) | 같음 · HTTP/2 를 켤 때 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-118028 | Important | Tomcat 11.0.0-M18–11.0.27 → 11.0.29 | 11.0.27 | RewriteValve 의 `[F]` · `[G]` · `[R]` 규칙 | 밸브 등록 0 | 해당 없음(판은 범위 안) | Tomcat 11.0.30 이 나오거나 Spring Boot 가 11.0.29 이상을 실을 때 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-121649 | Important | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | ExpiresFilter | 필터 등록 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-95517 | Moderate | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | 클러스터 EncryptInterceptor | 클러스터 설정 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-118436 | Moderate | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | CGI Servlet | CGI 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-106012 | Low | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | `security-role-ref` + `RequestDispatcher.forward()` | 그 설정 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-116561 | Low | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | Jakarta Authentication | 그 인증 0(인증은 자체 API 키 필터) | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-117517 | Low | Tomcat 11.0.19–11.0.27 → 11.0.29 | 11.0.27 | 클러스터 LoadBalancerDrainingValve(CVE-2026-25854 고침이 덜 됨) | 클러스터 · 밸브 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-118233 | Low | Tomcat 11.0.0-M1–11.0.27 → 11.0.29 | 11.0.27 | 애플리케이션이 HTTP upgrade 를 함 | WebSocket 끝점 · upgrade 호출 0 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://tomcat.apache.org/security-11.html |
| CVE-2026-19880 | MEDIUM | logback-classic 0.9.14–1.6.2 → 1.6.3(1.5 줄 고침판 없음) | 1.5.38 | SiftingAppender 의 MDC 값이 파일 경로로 감 | `logback-spring.xml` 에 SiftingAppender · MDC 0 | 해당 없음(판은 범위 안) | SiftingAppender 를 들일 때 · Spring Boot 가 logback 1.6.5 이상을 실을 때 | 2026-11-21 05:17~05:31 | https://logback.qos.ch/news.html |
| CVE-2026-104721 | MEDIUM | logback-classic 0.9.14–1.6.4 → 1.6.5(1.5 줄 고침판 없음) | 1.5.38 | 같음 | 같음 | 해당 없음(판은 범위 안) | 같음 | 2026-11-21 05:17~05:31 | https://logback.qos.ch/news.html |

## JSON — 서버 Jackson 3 3.1.8 · 서버 Jackson 2 2.21.8 · 서버 jackson-dataformat-yaml 2.21.8 · agent Jackson 2 2.18.12

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.11.2 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인 시각 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|---|
| CVE-2026-54512 | high | 2.10.0–2.18.7 → 2.18.8 · 2.19.0–2.21.3 → 2.21.4 · 3.0.0–3.1.3 → 3.1.4 | 3.1.8 · 2.21.8 · 2.18.12 | 다형 타입 역직렬화 | 다형 타입 설정 0 | 고침 | 다형 타입을 켤 때 | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-j3rv-43j4-c7qm |
| CVE-2026-83557 | medium | 2.11.0 – < 2.18.10 → 2.18.10 · 2.19.0 – < 2.21.6 → 2.21.6 · 3.0.0 – < 3.1.6 → 3.1.6 | 같음 | 다형 타입 검증기 | 다형 타입 설정 0 | 고침 | 같음 | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-gx83-3vf8-gh7j |
| CVE-2026-54514 | medium | < 2.18.8 → 2.18.8 · 2.19.0 – < 2.21.4 → 2.21.4 · 3.x < 3.1.4 → 3.1.4 | 같음 | `InetSocketAddress` 역직렬화 | 그 타입을 받는 자리 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-hgj6-7826-r7m5 |
| CVE-2026-54515 | medium | 2.8.0 – < 2.18.9 → 2.18.9 · 2.19.0 – < 2.21.5 → 2.21.5 · 3.1.0 – < 3.1.4 → 3.1.4 | 같음 | 대소문자 무시 역직렬화 | 그 설정 0 | 고침 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-5jmj-h7xm-6q6v |
| CVE-2026-91777 | high | databind 2.5.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 객체 ID 앞쪽 참조 완성이 제곱으로 느려짐 | 객체 ID(`@JsonIdentityInfo`) 사용 0 | 고침(agent 는 2.18.12 로 올림) | 객체 ID 를 쓸 때 | 2026-10-28 22:29 | https://github.com/advisories/GHSA-cxp5-3px4-pw24 |
| CVE-2026-91776 | high | databind 2.0.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 모르는 타입 ID 를 계속 붙잡음(다형 타입) | 다형 타입 설정 0 | 고침(같음) | 다형 타입을 켤 때 | 2026-10-28 22:29 | https://github.com/advisories/GHSA-wv8q-qhhj-9h54 |
| CVE-2026-89425 | high | core 2.8.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | `DataInput` 기반 파서의 오류 토큰 길이 제한 없음 | `DataInput` 파싱 0 | 고침(같음) | — | 2026-10-28 22:29 | https://github.com/advisories/GHSA-7hhh-6rmp-j9qf |
| CVE-2026-89407 | high | core 2.17.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 숫자 꼴 판별 정규식 ReDoS(믿을 수 없는 글자를 숫자로 볼 때) | agent 는 대상 앱 · 수집기의 JSON 을 트리로만 읽음(문자열→숫자 변환 0) · 서버는 고침판 | 고침(같음) | agent 가 JSON 을 객체로 바꿔 읽을 때 | 2026-10-28 22:29 | https://github.com/advisories/GHSA-p6pp-m3f8-5c89 |
| CVE-2026-110879 | critical | databind 3.0.0–3.1.7 → 3.1.8 · 2.19.0–2.21.7 → 2.21.8 · 2.10.0–2.18.11 → 2.18.12 | 3.1.8 · 2.21.8 · 2.18.12 | 다형 타입 검증기 | 다형 타입 설정 0 | 고침 | 다형 타입을 켤 때 | 2026-11-21 05:17~05:31 | https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph |
| CVE-2026-115876 | high | jackson-dataformat-yaml 2.19.0 이상(2.x 고침판 없음) | 2.21.8 | 믿을 수 없는 YAML 을 읽음 | 요청 본문 YAML 변환기를 뺐습니다 — YAML 본문은 `415` | 해당 없음(판은 범위 안) | YAML 을 읽는 입구를 들일 때 · 2.x 고침판이 나올 때 | 2026-11-21 05:17~05:31 | https://github.com/FasterXML/jackson-dataformats-text/security/advisories/GHSA-255r-36wv-4qpr |
| CVE-2026-110729 | low | jackson-core 3.2.0–3.2.3 → 3.2.4 | 3.1.8 | — | 실린 판이 범위 밖 | 해당 없음 | — | 2026-11-21 05:17~05:31 | https://github.com/FasterXML/jackson-core/security/advisories/GHSA-vvjm-vc27-799r |

## 저장소 — SQLite 3.54.1 (sqlite-jdbc 3.54.1.0)

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.11.2 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인 시각 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|---|
| CVE-2025-6965 | (SQLite 공지 표에 심각도 칸 없음) | < 3.50.2 → 3.50.2 | 3.54.1 | 공격자가 임의 SQL 을 넣을 수 있음 | SQL 은 고정 문장 · 요청 값은 바인딩으로만 | 고침 | — | 2026-10-28 20:13~21:31 | https://www.sqlite.org/cves.html |
| CVE-2026-11822 · CVE-2026-11824 | (같음) | < 3.53.2 → 3.53.2 | 3.54.1 | 임의 SQL + FTS5 + DEFENSIVE 꺼짐 | FTS5 사용 0 | 고침 | 전문 검색(FTS5)을 들일 때 | 2026-10-28 20:13~21:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119371 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | 임의 SQL 또는 믿을 수 없는 DB 의 view 로 CTE 를 수만 개 이어 붙임 | SQL 은 고정 문장 · 요청 값은 바인딩으로만 · 남이 만든 DB 파일을 열지 않음 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119372 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | FTS3/FTS4 + `SQLITE_ENABLE_FTS3_PARENTHESIS` 로 빌드 · 전문 검색 질의 | FTS3/FTS4 사용 0 | 고침 | 전문 검색을 들일 때 | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119373 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | FTS5 `detail=none` 표 + 접두 질의 | FTS5 사용 0 | 고침 | 같음 | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119374 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | FTS5 색인이 망가진 DB 파일 + secure-delete | FTS5 사용 0 | 고침 | 같음 | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119375 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | 조작한 DB · WAL 파일을 엶 | 남이 만든 DB · WAL 파일을 열지 않음 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119376 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | 임의 SQL 또는 믿을 수 없는 DB 의 view 로 `COLLATE` 를 수십만 번 이어 붙임 | SQL 은 고정 문장 · 요청 값은 바인딩으로만 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119377 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | `sqlite3_set_authorizer()` 로 열을 막고 임의 SQL 을 받음 | authorizer 사용 0 · SQL 은 고정 문장 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119378 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | R-Tree 확장 + 조작한 DB | R-Tree 사용 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119379 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | session 확장으로 조작한 changeset 을 적용 | session 확장 사용 0 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119591 | (같음) | < 3.54.0 → 3.54.0 | 3.54.1 | 임의 SQL 또는 조작한 view 의 큰 `VALUES` 위 `count(*)` | SQL 은 고정 문장 · 남이 만든 DB 파일을 열지 않음 | 고침 | — | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |
| CVE-2026-119586 | (같음) | 라이브러리 결함 아님(명령줄 도구 `.recover`) | 3.54.1 | 조작한 DB 를 `.recover` 로 풀어 다른 명령줄 도구에 `--safe` 없이 넣음 | jar 에 명령줄 도구 없음 · 문서의 `sqlite3` 예시에 `.recover` 0 | 해당 없음 | 운영 안내에 `.recover` 를 넣을 때 | 2026-11-21 05:17~05:31 | https://www.sqlite.org/cves.html |

- 이번에 새로 실은 SQLite 공지는 11건이고 행도 11 입니다(공지 하나에 행 하나). sqlite.org 가 「SQLite 결함 아님 — 재현되지 않음」으로 적은 6건(CVE-2026-51296 · 51297 · 51300 · 51302 · 51303 · 51304)은 싣지 않았습니다.

## JDK — 수집기를 띄우는 JDK | JDK for the collector

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.11.2 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인 시각 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|---|
| CVE-2026-114938(Java4Shell) | 8.1 High | Oracle Java SE 8u511b33-perf · 17.0.21.0.3 · 21.0.13.0.3 · 25.0.5.0.3 · 27.0.1.0.3 과 그 앞 판(OpenJDK 공지도 그 이하 전부) → 2026-11 정기 패치판 | 설치한 JDK(제품이 고르지 않음) | x86-64 AVX-512 VBMI 호스트 + 고쳐지지 않은 JDK | `/v1/traces` 의 `cursor` 값을 JDK Base64 디코더로 푸는 한 자리 · API 키를 켜면 키를 가진 호출자만 닿음 | 호스트 JDK 에 따름 | x86-64 호스트에 올릴 때 | 2026-11-21 05:17~05:31 | https://www.oracle.com/security-alerts/cspunov2026.html |

- 이 행의 판정은 위 세 낱말 밖입니다. 수집기를 띄우는 JDK 는 설치하는 쪽이 고르므로, 이 제품이 고칠 수도 없고 「해당 없음」이라 말할 수도 없습니다. Oracle 공지는 영향 판만 적고 고침판 번호 칸이 없어 번호를 적지 않았습니다. arm64 서버는 성립 조건이 없습니다 / The verdict depends on the JDK you install; arm64 hosts are not affected.

## 화면 — react-router 7.18.3 · vite 6.4.2(개발 도구)

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.11.2 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인 시각 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|---|
| CVE-2026-42211 | high | react-router 7.0.0–7.14.1 → 7.14.2 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-49rj-9fvp-4h2h |
| CVE-2026-55685 | high | react-router 7.0.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-chx6-hx7r-mcp5 |
| CVE-2026-53669 | medium | react-router 6.0.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-wrjc-x8rr-h8h6 |
| CVE-2026-53667 | medium | react-router 7.11.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-h8fp-f39c-q6mh |
| CVE-2026-53571 | medium | vite ≤ 6.4.2 → 6.4.3 (7 · 8 계열은 각자) | 6.4.2(개발 도구) | Windows 에서 vite 개발 서버의 `server.fs.deny` 우회 | 배포물은 빌드된 정적 파일이라 vite 개발 서버가 안 들어감 · 개발 서버는 127.0.0.1 에만 뜸 | 해당 없음(배포물 밖) | Windows 에서 개발할 때 · 개발 서버를 네트워크에 열 때(`--host`) · vite 가 배포물에 들어올 때 | 2026-10-28 20:13~21:31 | https://github.com/advisories/GHSA-fx2h-pf6j-xcff |

- vite 6.4.2 에는 위 공지를 포함해 공지 **7건**이 걸려 있습니다(npm 공지 조회 · 2026-10-28 23:10). 그중 5건은 6.4.3 까지 걸려 있어, 위 행의 고침판 6.4.3 으로 올려도 다 닫히지 않습니다. 7건 모두 vite 개발 서버와 그 편집기 열기 기능 쪽 공지라 배포물 밖입니다. 그래서 판을 올리지 않고 받아들였고, 위 「다시 볼 조건」이 되면 다시 봅니다.

## 이 대조표가 못 보는 것 | Known limits

- CI 의 `npm audit --omit=dev --audit-level=high` 는 배포물에 들어가는 의존성만 봅니다. 개발 서버(vite 등) 쪽은 원리적으로 못 봅니다.
- 의존성 그래프 제출과 Dependabot 알림은 GitHub 미러에서만 돕니다. 알림 켜기 · 보안 업데이트 PR 끄기는 저장소 설정입니다.
- 이 표는 손으로 만든 확인 결과라 낡습니다. 다음 판올림 때 다시 확인합니다.
