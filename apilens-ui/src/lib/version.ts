// Phase R12 (FR-D3, AC-D3-2): 버전 라벨 단일 거주지 (DG-01 — 하드코딩 3곳 치환).
//
// [Phase R12] AC-D3-1/AC-D3-2 — UI 내 'v0.x' 리터럴은 본 파일 1곳만 허용. 사용자 명시 결정 (D3 버전 bump 연쇄 차단).
// 회귀 가드 grep (설계 §9.3): grep -rn "v0\.[0-9]" apilens-ui/src --include='*.tsx' → 0 hit
// (version.ts 는 .ts — 유일 허용 거주지. 사용처: BrandNav / Setup.tsx wizard 헤더)
// [Phase K] (NFR-06): v0.2 → v0.3 — v0.3 릴리스 UI 표시 라벨 일관성 (package.json 0.3.0 동기화 보강). 사용자 명시 결정 (R14 v0.3 라운드).
// [Phase O / R18] (FR-06): v0.3 → v0.4 — v0.4.0 릴리스 UI 표시 라벨 (package.json 0.4.0 동기). R18 dev-frontend 가 package.json 만 bump 하고 이 SSOT 를 누락 → 릴리스 전 사용자 지적으로 회수.
// [Phase R19] (FR-06): v0.4 → v0.5 — v0.5.0 릴리스 UI 표시 라벨 (package.json 0.5.0 동기).
//   R18 회귀(package.json 만 bump, 이 SSOT 누락)를 구조로 차단하려고 이번 라운드에
//   src/test/version.test.ts 를 신설했다 — package.json version 의 앞 두 자리와 일치를 단언.
//   표시 위치는 BrandNav 로고 옆(화면 왼쪽 위). major.minor 만 표기한다(patch 미표기가 기존 규약).
// [Phase R20] R20/AC-13-2 (FR-13): v0.5 → v0.6 — v0.6.0 릴리스 UI 표시 라벨 (package.json 0.6.0 동기,
//   lockfile 은 npm install 로만 동기 — 손편집 금지가 R19 D-14 확정). version.test.ts 자동 가드가 앞 두 자리 일치를 단언.
// [Phase R25] FR-25-08 (설계 §6.3 표 3행): v0.6 → v0.7 — v0.7.0 릴리스 UI 표시 라벨.
//   사용자 명시 결정. CLAUDE.md ':28' 「9. **버전: `0.6.2`** (`build.gradle.kts` 의 `allprojects
//   { version = ... }` 줄이 SSOT …)」 — 제품 버전의 단일 원본은 build.gradle.kts 이고, 이 파일은
//   **화면 표시 라벨**의 단일 거주지다(둘은 다른 축이라 값만 따라간다).
//   ★이번 라운드는 package.json 이 0.6.2 로 두 세대 뒤처져 있던 것을 함께 맞춘다 —
//     「화면을 여는 첫 라운드에서 맞춘다」가 2026-08-28 확정이고 이번이 그 라운드다.
//     그래서 이 파일과 package.json 을 **같이** 올린다 (한쪽만 올리면 version.test.ts 가 빨개진다).
export const APP_VERSION = 'v0.7';
