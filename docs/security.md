# 보안 공지 대조표 | Security advisories check (v0.10.0)

ApiLens v0.10.0 이 쓰는 부품에 대해 **공식 페이지에서 직접 확인한** 보안 공지만 싣습니다.
공식 페이지를 열지 못했거나 공지 번호를 찾지 못한 항목은 싣지 않았습니다.

This table lists only advisories that were **checked on the official source page** for the components that
ApiLens v0.10.0 ships with. Advisories that could not be confirmed on an official page are not listed.

- 확인 시각: 2026-10-28 20:13 ~ 21:31 · Jackson 2.18.11 고침 공지 4건은 22:29 (KST) · 이 문서는 그 시각의 공지 내용입니다. 그 뒤에 나온 공지는 들어 있지 않습니다.
- 「v0.10.0 판」은 빌드가 실제로 고른 판입니다(`./gradlew dependencies` · npm 잠금 파일).
- 「성립 조건」은 공지가 말하는 조건이고, 「ApiLens」 칸은 그 조건이 이 제품 코드 · 설정에 있는지 확인한 결과입니다.
- 판정: **고침** = 이번 판올림으로 고침판 위에 있습니다 · **해당 없음** = 고침판 위이거나 조건이 이 제품에 없습니다.

## 서버(collector) — Spring Boot 4.1.2 · Spring Framework 7.0.10 · Tomcat 11.0.27

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.10.0 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|
| CVE-2026-47890 | LOW | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.10 | SSE 로 view fragment 를 보냄 | SSE · view fragment 쓰는 코드 0 | 고침 | — | https://spring.io/security/cve-2026-47890 |
| CVE-2026-47888 | MEDIUM | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.10 | Spring RSocket 사용 | RSocket 의존 0 | 고침 | — | https://spring.io/security/cve-2026-47888 |
| CVE-2026-59280 | MEDIUM | Framework 6.2.0–6.2.19 · 7.0.0–7.0.8 → 7.0.9 | 7.0.10 | FreeMarker 뷰 이름을 밖의 값으로 만듦 | FreeMarker 의존 0 | 고침 | — | https://spring.io/security/cve-2026-59280 |
| CVE-2026-41855 | HIGH | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.10 | 믿을 수 없는 JMS 환경 | JMS 의존 0 | 고침 | — | https://spring.io/security/cve-2026-41855 |
| CVE-2026-41852 | LOW | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.10 | 사용자가 준 SpEL 식을 평가 | SpEL 평가 코드 0 | 고침 | — | https://spring.io/security/cve-2026-41852 |
| CVE-2026-41842 | HIGH | Framework 6.2.0–6.2.18 · 7.0.0–7.0.7 → 7.0.8 | 7.0.10 | 파일 시스템 정적 자원 + 버전 자원 설정 | 버전 자원 설정 0 | 고침 | 정적 자원 체인을 설정할 때 | https://spring.io/security/cve-2026-41842 |
| CVE-2026-22731 | HIGH | Boot 3.4.0–3.4.14 · 3.5.0–3.5.11 · 4.0.0–4.0.3 → 3.5.12 · 4.0.4 | 4.1.2 | Actuator + 사용자 health group 을 `additional-path` 로 본 서버에 노출 | health group · `additional-path` 설정 0 | 해당 없음 | health group 을 더할 때 | https://spring.io/security/cve-2026-22731 |
| CVE-2026-22733 | HIGH | Boot 3.4.0–3.4.14 · 3.5.0–3.5.11 · 4.0.0–4.0.3 → 3.5.12 · 4.0.4 | 4.1.2 | Actuator + CloudFoundry Actuator 경로 아래 인증 endpoint | CloudFoundry 설정 0 | 해당 없음 | — | https://spring.io/security/cve-2026-22733 |
| CVE-2026-40976 | CRITICAL | Boot 4.0.0–4.0.5 → 4.0.6 | 4.1.2 | Spring Security 기본 필터 체인에 기댐 | Spring Security 의존 0(인증은 자체 API 키 필터) | 해당 없음 | Spring Security 를 들일 때 | https://spring.io/security/cve-2026-40976 |
| CVE-2026-101356 | Important | Tomcat 10.1 → 10.1.61 · 11 → 11.0.27 | 11.0.27 | HTTP/2 비동기 처리 | HTTP/2 설정 0 | 고침 | — | https://tomcat.apache.org/security-11.html |
| CVE-2026-101296 | Important | Tomcat 10.1 → 10.1.61 · 11 → 11.0.27 | 11.0.27 | JNDIRealm + GSSAPI 아닌 SASL | JNDI realm 0 | 고침 | — | https://tomcat.apache.org/security-11.html |
| CVE-2024-12798 | MEDIUM | logback-core < 1.5.13 → 1.5.13 | 1.5.38 | 설정 파일을 공격자가 고칠 수 있음 | 설정은 jar 안 | 고침 | — | https://github.com/advisories/GHSA-pr98-23f8-jwxv |
| CVE-2024-12801 | LOW | logback-core < 1.5.13 → 1.5.13 | 1.5.38 | 같음 | 같음 | 고침 | — | https://github.com/advisories/GHSA-6v67-2wr5-gvf4 |

## JSON — 서버 Jackson 3 3.1.7 · 서버 Jackson 2 2.21.7 · agent Jackson 2 2.18.12

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.10.0 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|
| CVE-2026-54512 | high | 2.10.0–2.18.7 → 2.18.8 · 2.19.0–2.21.3 → 2.21.4 · 3.0.0–3.1.3 → 3.1.4 | 3.1.7 · 2.21.7 · 2.18.12 | 다형 타입 역직렬화 | 다형 타입 설정 0 | 고침 | 다형 타입을 켤 때 | https://github.com/advisories/GHSA-j3rv-43j4-c7qm |
| CVE-2026-83557 | medium | 2.11.0 – < 2.18.10 → 2.18.10 · 2.19.0 – < 2.21.6 → 2.21.6 · 3.0.0 – < 3.1.6 → 3.1.6 | 같음 | 다형 타입 검증기 | 다형 타입 설정 0 | 고침 | 같음 | https://github.com/advisories/GHSA-gx83-3vf8-gh7j |
| CVE-2026-54514 | medium | < 2.18.8 → 2.18.8 · 2.19.0 – < 2.21.4 → 2.21.4 · 3.x < 3.1.4 → 3.1.4 | 같음 | `InetSocketAddress` 역직렬화 | 그 타입을 받는 자리 0 | 고침 | — | https://github.com/advisories/GHSA-hgj6-7826-r7m5 |
| CVE-2026-54515 | medium | 2.8.0 – < 2.18.9 → 2.18.9 · 2.19.0 – < 2.21.5 → 2.21.5 · 3.1.0 – < 3.1.4 → 3.1.4 | 같음 | 대소문자 무시 역직렬화 | 그 설정 0 | 고침 | — | https://github.com/advisories/GHSA-5jmj-h7xm-6q6v |
| CVE-2026-91777 | high | databind 2.5.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 객체 ID 앞쪽 참조 완성이 제곱으로 느려짐 | 객체 ID(`@JsonIdentityInfo`) 사용 0 | 고침(agent 는 2.18.12 로 올림) | 객체 ID 를 쓸 때 | https://github.com/advisories/GHSA-cxp5-3px4-pw24 |
| CVE-2026-91776 | high | databind 2.0.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 모르는 타입 ID 를 계속 붙잡음(다형 타입) | 다형 타입 설정 0 | 고침(같음) | 다형 타입을 켤 때 | https://github.com/advisories/GHSA-wv8q-qhhj-9h54 |
| CVE-2026-89425 | high | core 2.8.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | `DataInput` 기반 파서의 오류 토큰 길이 제한 없음 | `DataInput` 파싱 0 | 고침(같음) | — | https://github.com/advisories/GHSA-7hhh-6rmp-j9qf |
| CVE-2026-89407 | high | core 2.17.0–2.18.10 → 2.18.11 · 2.19.0–2.21.6 → 2.21.7 · 3.0.0–3.1.6 → 3.1.7 | 같음 | 숫자 꼴 판별 정규식 ReDoS(믿을 수 없는 글자를 숫자로 볼 때) | agent 가 읽는 JSON 은 수집기 응답뿐 · 서버는 고침판 | 고침(같음) | agent 가 다른 출처 JSON 을 읽을 때 | https://github.com/advisories/GHSA-p6pp-m3f8-5c89 |

## 저장소 — SQLite 3.53.4 (sqlite-jdbc 3.53.4.0)

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.10.0 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|
| CVE-2025-6965 | (SQLite 공지 표에 심각도 칸 없음) | < 3.50.2 → 3.50.2 | 3.53.4 | 공격자가 임의 SQL 을 넣을 수 있음 | SQL 은 고정 문장 · 요청 값은 바인딩으로만 | 고침 | — | https://www.sqlite.org/cves.html |
| CVE-2026-11822 · CVE-2026-11824 | (같음) | < 3.53.2 → 3.53.2 | 3.53.4 | 임의 SQL + FTS5 + DEFENSIVE 꺼짐 | FTS5 사용 0 | 고침 | 전문 검색(FTS5)을 들일 때 | https://www.sqlite.org/cves.html |

## 화면 — react-router 7.18.3 · vite 6.4.2(개발 도구)

| 공지 | 공식 심각도 | 영향 범위 → 고침판 | v0.10.0 판 | 성립 조건 | ApiLens | 판정 | 다시 볼 조건 | 확인한 곳 |
|---|---|---|---|---|---|---|---|---|
| CVE-2026-42211 | high | react-router 7.0.0–7.14.1 → 7.14.2 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | https://github.com/advisories/GHSA-49rj-9fvp-4h2h |
| CVE-2026-55685 | high | react-router 7.0.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | https://github.com/advisories/GHSA-chx6-hx7r-mcp5 |
| CVE-2026-53669 | medium | react-router 6.0.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | https://github.com/advisories/GHSA-wrjc-x8rr-h8h6 |
| CVE-2026-53667 | medium | react-router 7.11.0 – < 7.18.0 → 7.18.0 | 7.18.3 | — | 고침판 위 | 해당 없음 | — | https://github.com/advisories/GHSA-h8fp-f39c-q6mh |
| CVE-2026-53571 | medium | vite ≤ 6.4.2 → 6.4.3 (7 · 8 계열은 각자) | 6.4.2(개발 도구) | Windows 에서 vite 개발 서버의 `server.fs.deny` 우회 | 배포물은 빌드된 정적 파일이라 vite 개발 서버가 안 들어감 · 개발 서버는 127.0.0.1 에만 뜸 | 해당 없음(배포물 밖) | Windows 에서 개발 서버를 네트워크에 열 때 | https://github.com/advisories/GHSA-fx2h-pf6j-xcff |

## 이 대조표가 못 보는 것 | Known limits

- CI 의 `npm audit --omit=dev --audit-level=high` 는 배포물에 들어가는 의존성만 봅니다. 개발 서버(vite 등) 쪽은 원리적으로 못 봅니다.
- 의존성 그래프 제출과 Dependabot 알림은 GitHub 미러에서만 돕니다. 알림 켜기 · 보안 업데이트 PR 끄기는 저장소 설정입니다.
- 이 표는 손으로 만든 확인 결과라 낡습니다. 다음 판올림 때 다시 확인합니다.
