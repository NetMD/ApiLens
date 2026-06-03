// vitest 셋업 — D-02: passWithNoTests 미설정. format.test.ts / layout.test.ts 다수 케이스로 fail 회피.
// vite.config.ts와 분리하여 dev 서버 설정과 test 설정의 책임을 분리한다.
//
// react 플러그인은 의도적으로 포함하지 않음 — vitest 2.x가 자체 vite 사본을 dep으로 가져
// 외부 @vitejs/plugin-react의 vite 타입과 mismatch가 발생한다 (exactOptionalPropertyTypes).
// 현재 테스트 대상은 순수 함수(format.test, layout.test)뿐이라 플러그인 없이 충분. JSX 테스트가 필요해지면
// 그때 플러그인 mismatch를 해결한다.
//
// F2(A-08): environment를 jsdom → happy-dom 전환. happy-dom 14.x는 ResizeObserver native 지원으로
// React Flow 렌더 환경에 더 우호적이며 jsdom 대비 빠르다. 단 layout/format 테스트는 순수 함수라
// environment 영향 0이지만 향후 시각 컴포넌트 테스트 확장 대비.
import { defineConfig } from 'vitest/config';

export default defineConfig({
  // [R10] FE-1 부수 정정 — esbuild JSX 자동 런타임 명시 (React 19 호환).
  // baseline vitest config 에서 jsx automatic 누락으로 .tsx 테스트들이
  // ReferenceError: React is not defined 로 fail 하던 회귀 차단.
  // FirstRunGuard.test.tsx (5건) + useSearchPreservingNavigate.test.tsx (3건) 13건이 본 회귀에 해당.
  esbuild: {
    jsx: 'automatic',
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
