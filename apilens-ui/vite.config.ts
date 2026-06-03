// Vite 설정 — D-08: proxy target은 명시적 IPv4 (127.0.0.1).
// localhost를 쓰면 macOS에서 IPv6(::1)로 resolve되어 Spring Boot의 IPv4 바인딩과
// mismatch가 발생할 수 있다. 운영 환경 트랩 회피를 위한 박제.
//
// Tailwind 4: PostCSS 통합 대신 @tailwindcss/vite 플러그인 사용.
// PostCSS 통합은 lightningcss 4.x와의 ScannerOptions 호환 이슈가 있어 vite 플러그인이 더 안전.
// CSS-first @theme(D-01) 토큰은 src/index.css에 그대로 유지 — 컬러 단일 출처(NFR-05).
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/v1': {
        target: 'http://127.0.0.1:8765',
        changeOrigin: false, // same-host (127.0.0.1:5173 → 127.0.0.1:8765)
      },
    },
  },
});
