// Phase R12 (FR-D3, AC-D3-2): 버전 라벨 단일 거주지 (DG-01 — 하드코딩 3곳 치환).
//
// [Phase R12] AC-D3-1/AC-D3-2 — UI 내 'v0.x' 리터럴은 본 파일 1곳만 허용. 사용자 명시 결정 (D3 버전 bump 연쇄 차단).
// 회귀 가드 grep (설계 §9.3): grep -rn "v0\.[0-9]" apilens-ui/src --include='*.tsx' → 0 hit
// (version.ts 는 .ts — 유일 허용 거주지. 사용처: BrandNav / Setup.tsx wizard 헤더)
// [Phase K] (NFR-06): v0.2 → v0.3 — v0.3 릴리스 UI 표시 라벨 일관성 (package.json 0.3.0 동기화 보강). 사용자 명시 결정 (R14 v0.3 라운드).
export const APP_VERSION = 'v0.3';
