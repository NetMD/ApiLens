Phase F2 fix #2 — mockup 정신 그대로 레이아웃 재정의 + 작은 fix 묶음

## 컨텍스트
F2 + sample-app 풍부화 후 분기 trace로 노드 그래프의 mockup 정체성 시각 확인됨.
다만 사용자가 정의한 mockup의 4단 수직 레이아웃과 어긋남 (현재는 우측 sidebar).
이번 작업은 mockup 정신 그대로 레이아웃 재배치 + 작은 fix 4개.

## 정답 mockup
docs/mockups/trace-detail.html (이전 작업에서 fixture로 박제됨).
이 파일의 레이아웃 구조를 정답으로 둘 것.

## 변경 1: 레이아웃 — 우측 sidebar → 수직 4단

mockup HTML 구조 그대로:

1. 상단 헤더 bar (현재 그대로)
   - back + title (rootOperation) + spanCount·duration·status

2. 그래프 영역
   - 현재 좌측 그래프 그대로
   - 그래프 영역이 sidebar 비활성 시 화면 가로 전체 차지 (이미 그렇게 동작 중일 가능성)
   - 그래프 자연 높이 (min-height 정도만, 강제 비율 없음)

3. 범례 가로줄 (mockup의 색깔 점 + node size 표기)
   - 그래프 바로 아래, 가로 한 줄
   - browser / service / db / external 색깔 점 + 라벨
   - 우측에 "node size = duration"
   - 현재 그래프 안 우하단 또는 좌하단에 있으면 → 그래프 밖 별도 가로 줄로 분리

4. 선택 노드 카드 (이게 큰 변경)
   - 현재 우측 sidebar → 그래프 아래 카드로 이동
   - 배경: 연한 회색 (Tailwind bg-stone-100 또는 bg-neutral-100)
   - 둥근 모서리, 패딩
   - 콘텐츠: 헤더 (operationName + duration·status) + attributes + payload IN/OUT
   - 카드 너비: 화면 가로 전체 또는 max-width로 가운데 정렬
   - 선택 안 한 상태에선 카드 자체 안 보임 (또는 placeholder "Click a node to see details")
   - payload OUT 비었을 때: "(no response body)" 회색 placeholder

비율 강제 금지:
- 그래프와 카드 사이 5:5 강제하지 말 것
- 그래프는 자연 크기 (분기 많은 trace는 크게, 단순 chain은 작게)
- 카드는 콘텐츠 크기 (payload 짧으면 작게)
- min-height만 적당히 (그래프 400~500px, 카드 200~300px)

## 변경 2: 라벨 폰트 크기 (노드 시인성)

위치: apilens-ui/src/components/TraceGraph/SpanNode.tsx

현재: 라벨이 노드 점에 비해 가로로 너무 길게 보임. mockup은 노드 옆/위 작은 텍스트.

수정: 라벨 font-size를 mockup 수준으로 (text-xs = 12px 또는 text-[11px]).
현재 값 확인 후 한 단계 작게.

## 변경 3: jdbc 노드 최소 radius

위치: apilens-ui/src/components/TraceGraph/nodeSizing.ts (또는 동등 위치)

현재: 1ms span의 radius가 너무 작아 거의 점. 클릭하기 어려움.

수정: radius floor 추가.
```typescript
function durationToRadius(durationMs: number): number {
  const computed = 4 + Math.log10(durationMs + 1) * 1.5
  return Math.max(6, Math.min(12, computed))  // floor 6, ceiling 12
}
```

기존 공식 + Math.max(6, ...) 만 추가. 단위 테스트 1건 추가 (1ms → r=6).

## 변경 4: Payload OUT 빈 상태 placeholder

위치: apilens-ui/src/components/PayloadView.tsx (또는 SpanInspector.tsx 안)

현재: payload OUT 없을 때 헤더만 보이고 그 아래 빈 공간.

수정: "PAYLOAD OUT" 헤더 아래 body 박스에 "(no response body)" 회색 작은 글씨.
text-xs text-stone-400 정도.

## 변경 5: agent에서 framework class ignore

위치: apilens-agent/src/main/java/io/apilens/agent/instrument/InstrumentationInstaller.java
또는 advice 매처 정의된 곳.

현재: BasicErrorController, BasicErrorController#error 등 Spring 내부 class도 trace 발생.
운영자 관점에서 noise.

수정: 기본 ignore 패키지 추가:
- org.springframework.*
- org.apache.tomcat.*
- org.apache.catalina.*
- jakarta.*
- javax.*
- java.*

이미 ByteBuddy ignore() 매처에 일부 있을 가능성 (자기-instrument 회피 코드 안에). 거기에 추가하거나
별도 ignore 룰로.

매처 패턴:
```java
not(nameStartsWith("org.springframework"))
    .and(not(nameStartsWith("org.apache.tomcat")))
    .and(not(nameStartsWith("org.apache.catalina")))
    .and(not(nameStartsWith("jakarta")))
    .and(not(nameStartsWith("javax")))
    .and(not(nameStartsWith("java")))
```

단, Spring Data JPA의 SimpleJpaRepository 는 사용자 entity 다루는 거라 추적 가치 있음.
이건 그대로 두고 BasicErrorController 같은 Spring framework 내부 controller만 빼야 함.

따라서 정확한 매처:
- `org.springframework.boot.autoconfigure.*` (BasicErrorController 위치)
- `org.springframework.web.servlet.handler.*` (DispatcherServlet 내부)
- 위 jakarta/javax/java는 그대로
- `org.springframework.data.*` 는 제외 안 함 (SimpleJpaRepository 유지)

대안 — whitelist 패턴:
- agent 옵션 `apilens.include.packages` 추가 (예: "com.example.*")
- 옵션 미지정 시 위 ignore 룰 적용
- 옵션 지정 시 그 패키지만 instrument

이번 phase는 단순화: ignore 룰만 추가. whitelist 옵션은 v0.2.

검증:
- 단위 테스트 추가: SpringMatchers 또는 동등 위치에서 BasicErrorController className → instrument 대상 아님
- sample-app smoke: 잘못된 URL 호출 시 (예: /users/99999) BasicErrorController trace 0개 생성
- UserController#get ERR trace는 그대로 1개 생성 (Phase F1 후속의 status_code 기반 ERROR)

## 작업 외 (이번엔 안 함)
- 마스킹 라벨 배지 (v0.2)
- agent의 arg0 → 실제 인자 이름 (v0.2, agent + compile 옵션 변경)
- 양방향 애니메이션 (v0.2)
- whitelist 패턴 옵션 (v0.2)
- MSA (v0.3)

## 검증 (사용자가 수행)
너는 코드 + 단위 테스트. 사용자가 실제 동작 검증.

자동 검증:
1. ./gradlew clean test
2. ./gradlew :apilens-agent:shadowJar
3. cd apilens-ui && npm run build

수동 smoke 체크리스트:
[ ] sample-app 재기동 후 잘못된 URL 호출 → BasicErrorController trace 0개
[ ] UserController#get 404 → trace 1개 (status=ERROR)
[ ] 노드 그래프 화면:
    [ ] 그래프가 화면 가로 전체 차지 (좌측만 아님)
    [ ] 노드 라벨 폰트 크기 작아짐 (노드 점이 더 잘 보임)
    [ ] jdbc.execute 노드가 클릭 가능한 크기 (6px 이상)
    [ ] 그래프 아래 가로 범례 한 줄
    [ ] 그래프 아래 선택 노드 카드 (배경 연한 회색)
    [ ] 카드의 PAYLOAD OUT 비었을 때 "(no response body)" placeholder
    [ ] 노드 클릭 시 카드 등장, 다른 노드 클릭 시 카드 내용 갱신
    [ ] 그래프 빈 영역 클릭 시 카드 사라짐 (또는 placeholder)

## 주의
- 우측 sidebar 컴포넌트 (SpanInspector?) 제거 또는 재구성. 카드로 변환.
- React Flow 자체는 유지. 변경은 레이아웃 (Grid/Flex) 수준.
- Phase A~E2 코드 + F1 코드 미수정 (변경 5의 agent matcher 제외).
- mockup hex 색상 변경 금지.
- "테스트 통과" 단정 보고 금지.

작업 시작 전:
- docs/mockups/trace-detail.html (정답)
- 현재 TraceDetail.tsx 레이아웃
- 현재 SpanInspector.tsx (제거 또는 재구성 대상)
- 현재 SpringMatchers 또는 InstrumentationInstaller의 ignore 패턴