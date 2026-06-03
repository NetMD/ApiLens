// ESLint flat config (D-04) — ESLint 9 표준.
// no-explicit-any: error / no-console: error / no-restricted-imports로 d3 차단(D-05 부분 유지).
//
// F2부터 @xyflow/react 는 허용 (TraceDetail 노드 그래프). @xyflow/d3* 와 d3* 는 차단 유지.
import js from '@eslint/js';
import tseslint from 'typescript-eslint';
import reactHooks from 'eslint-plugin-react-hooks';
import reactRefresh from 'eslint-plugin-react-refresh';
import globals from 'globals';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'coverage', '*.config.js', '*.config.ts'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      '@typescript-eslint/no-explicit-any': 'error',
      'no-console': 'error',
      'no-restricted-imports': [
        'error',
        {
          patterns: [
            // F2부터 @xyflow/react는 허용 (TraceGraph 사용). @xyflow/d3*는 차단 유지.
            {
              group: ['@xyflow/d3*'],
              message:
                '@xyflow/d3 family는 사용하지 않는다. 노드 그래프는 @xyflow/react 단독으로 처리.',
            },
            {
              group: ['d3', 'd3-*'],
              message:
                'd3는 사용하지 않는다. 노드 그래프는 @xyflow/react 단독으로 처리.',
            },
          ],
        },
      ],
    },
  },
  {
    // 테스트 파일은 vitest 글로벌 허용
    files: ['**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2022,
      },
    },
    rules: {
      'no-console': 'off',
    },
  },
);
