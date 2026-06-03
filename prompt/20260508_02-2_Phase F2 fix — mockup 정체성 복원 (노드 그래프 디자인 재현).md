Phase F2 fix — mockup 정체성 복원 (노드 그래프 디자인 재현)

## 컨텍스트
F2가 동작은 하지만 mockup의 시각적 정체성에서 멀어짐. 현재는 React Flow 기본 박스
노드를 쓰고 있어 ApiLens의 mind-map 느낌이 안 살아남. 이번 작업은 mockup HTML을
정답으로 두고 디자인 충실도를 끌어올리는 작업.

## 정답 mockup
docs/mockups/trace-detail.html 로 첨부됨 (사용자 제공 SVG mockup).
이 파일을 fixture로 두고 색상/레이아웃/타이포그래피/간격을 그대로 따라갈 것.

## 사용자 결정 사항 (이번 작업의 핵심 변경)

### 1. 노드 = 원, 라벨은 노드 옆/위/아래
- 노드 모양: <circle> (직사각형 아님)
- 노드 안엔 텍스트 없음
- operationName은 노드 옆 또는 위에 별도 텍스트로
- React Flow의 hit area는 원보다 큰 invisible bounding box로 잡아 dagre 충돌 회피

### 2. 노드 크기 = duration 비례 (log scale)
- 이전 결정 ("균일 크기") 번복
- mockup의 `node size = duration` 범례 그대로 살림
- 운영자 가치: 노드 한 번 봐도 "여기서 시간 썼다" 직관
- 매핑: radius = 4 + log10(durationMs + 1) * 1.5
  - 1ms → r ≈ 4.5
  - 10ms → r ≈ 5.5
  - 100ms → r ≈ 7
  - 1000ms → r ≈ 9
  실제 운영 trace 범위에서 시각적으로 분별 가능한 크기 차이.
- 필요 시 위 공식 미세 조정 (5px ~ 12px 범위)

### 3. fitView 자동 (가장 시급한 fix)
- 현재 노드들이 그래프 영역의 한쪽 구석에. 운영자 첫 인상 망침.
- React Flow의 fitView prop 또는 useReactFlow().fitView() 활용
- 노드 데이터 로드 후 자동 호출, 마진 0.2 정도

### 4. Sidebar 디자인 mockup 그대로
- 배경: var(--color-background-secondary) 같은 연한 회색 카드
  Tailwind: bg-stone-100 또는 bg-neutral-50
- 둥근 모서리: rounded-lg
- 헤더: operationName (mono, font-medium, text-sm) + duration · status (text-xs, secondary)
- "PAYLOAD IN" / "PAYLOAD OUT": uppercase, text-xs, letter-spacing-wide, secondary color
- payload body 박스: 흰 배경 + 0.5px border + rounded-md + mono + 작은 글씨

### 5. 범례 + 선택 배지
- 그래프 하단에 가로 범례:
  ● service (#1D9E75) ● db (#378ADD) ● external (#EF9F27) ● browser (#7F77DD)
  추가 우측: "node size = duration"
- 선택된 노드 아래에 작은 파란 알약 "selected" — 이건 mockup의 디자인 요소,
  복원하면 어떤 노드가 선택됐는지 추가로 인지 가능
- (선택) 미니맵은 추가하지 말 것 — mockup에 없음, 시각적 노이즈

### 6. error 표시
- mockup엔 error 케이스 직접 안 그려져있지만, 우리 v0.1 결정 유지:
  - error span: 노드는 원래 색 그대로 + 빨간 ring (border-2 또는 stroke-2 #E24B4A)
  - 약간 큰 크기 (radius +2)

### 7. Payload OUT 빈 상태 placeholder
- payload OUT이 없는 span (예: void return) 시
- 현재: 빈 공간만 보임
- 수정: "PAYLOAD OUT" 헤더 아래 회색 작은 글씨 "(no response body)"

### 8. 라벨 텍스트 위치
- mockup의 controller 노드: 라벨은 노드 위에 + sub-라벨 아래 (POST /api/orders)
- mockup의 OrderService: 라벨이 노드 위 + .create는 그 아래
- mockup의 leaf 노드들: 라벨이 노드 오른쪽
- v0.1 단순화: 모든 노드 라벨을 **노드 위**에 일관되게 배치 (가운데 정렬). 라벨이
  노드 오른쪽으로 가는 mockup의 leaf 디테일은 v0.2.

## 변경 안 할 것 (이번 fix 범위 외)

- 마스킹 라벨 배지 ("masked: ssn") — server response 확장 필요, v0.2
- agent의 arg0 → 실제 인자 이름 — agent 변경 필요, v0.2
- 양방향 애니메이션 — v0.2
- 분기 trace 시나리오 (sample-app 풍부화) — 별도 phase
- MSA 서비스 grouping — v0.3

## 작업 위치

apilens-ui/src/components/TraceGraph/
├── index.tsx              # fitView 추가, 범례 추가
├── SpanNode.tsx           # 원 + 옆 라벨 + duration 비례 크기로 재구현
├── layout.ts              # node bounding box를 라벨 포함 크기로 보정
├── nodeSizing.ts (신규)   # log scale duration → radius 헬퍼 + 단위 테스트
└── tests/
    ├── layout.test.ts     # 기존
    └── nodeSizing.test.ts # 신규

apilens-ui/src/components/
├── SpanInspector.tsx      # 카드 디자인 mockup 정확히
└── PayloadView.tsx        # IN/OUT 헤더 스타일, 빈 상태 placeholder

## 검증 (사용자가 수행)

너는 코드 + 단위 테스트까지. 시각적 검증은 사용자.

자동 검증:
1. ./gradlew clean test  -- 35+ 단위 테스트 통과 (nodeSizing 5건 추가)
2. cd apilens-ui && npm run build  -- 빌드 통과

시각 체크리스트:
[ ] 노드가 원 모양 (직사각형 아님)
[ ] 라벨이 노드 위 (안 아님)
[ ] 노드 크기가 duration 비례 (sample-app 4 spans 중 jdbc.execute가 가장 작거나 가장 큼 — duration에 따라)
[ ] fitView 동작 — 노드들이 그래프 중앙에 자동 정렬
[ ] sidebar에 연한 회색 카드 배경
[ ] PAYLOAD IN / OUT 헤더가 uppercase + 작은 회색
[ ] payload body가 흰 배경 + 미세 border 박스
[ ] 범례 보임 (service/db/external/browser + node size = duration)
[ ] 선택 시 노드 아래 "selected" 알약
[ ] Payload OUT 없는 노드 시 "(no response body)" placeholder
[ ] error trace 시 빨간 ring (404 호출로 검증)

## 주의

- mockup HTML의 색상 hex (#1D9E75 등)는 F1 tailwind 토큰과 일치 — 변경 금지
- mockup의 var(--color-background-secondary) 같은 CSS 변수는 우리 환경에 맞춰
  Tailwind 등가 클래스로 매핑 (stone/neutral palette)
- React Flow를 버리지 말 것 — 현재 구조 유지하면서 커스텀 노드만 다시 짜기
- "테스트 통과" 단정 보고 금지
- F1 코드 + Phase A~E2 코드 미수정

작업 시작 전:
- docs/mockups/trace-detail.html (이 작업 정답)
- 현재 SpanNode.tsx, TraceGraph/index.tsx, SpanInspector.tsx 코드
- React Flow 12 커스텀 노드 패턴 (https://reactflow.dev/learn/customization/custom-nodes)
- dagre 노드 사이즈 + 라벨 패딩 패턴