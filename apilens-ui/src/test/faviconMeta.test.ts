// [R29/FR-R29-07 · AC-R29-37] 파비콘 메타 시험 — `index.html` 의 아이콘 링크 3 ↔ `public/` 실물 · PNG 두 장의 글자 청크 0 ·
//   끝(IEND) 뒤 바이트 0. 아이콘을 바꿔 끼우는 라운드에서 링크가 빈 파일을 가리키거나, 편집기 메타데이터(만든 도구 ·
//   경로 같은 글자)가 공개 자산에 실려 나가는 것을 막는다.
//
// 경로 기준 = vitest 작업 폴더(apilens-ui 루트) — `import.meta.url` 꼴은 쓰지 않는다(vitest 의 vite 변환이 그 글자를
//   뿌리 기준 `/index.html` 로 바꿔 파일 경로가 안 된다 · GT-R29-16 · 선례 agent-option-builder.test.ts).
// 판정력: 링크 하나를 지운 `index.html` 문자열 사본을 검사 함수에 넣어 빨강을 본다 — `index.html` 파일은 고치지 않는다.
// 범위 밖: `eXIf` 청크(지금 68 B · 글자 0 · 색공간 · 크기뿐) · PNG 실제 크기 ↔ `sizes` 대조.
// ★타입 검사(tsc -b)는 시험 파일을 빼므로 이 파일의 형 오류는 vitest 실행이 잡는다.
//
// 검증 의무 (정방향 동사 — EXT-003 lock-in 회귀 가드):
//   linksEveryIconToAFileInPublic · keepsThePngIconsFreeOfTextChunksAndTrailingBytes · failsWhenALinkIsMissingFromTheHtml
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const HTML_PATH = resolve(process.cwd(), 'index.html');
const PUBLIC_DIR = resolve(process.cwd(), 'public');
/** 검사 대상 PNG — 링크가 가리키는 PNG 둘. */
const PNG_ICONS = ['favicon.png', 'apple-touch-icon.png'] as const;
const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const TEXT_CHUNKS = new Set(['tEXt', 'zTXt', 'iTXt']);

/** `public/` 안 파일을 읽는다 — 링크 `href` 의 앞 `/` 는 떼고 본다 · 없으면 null. */
function readPublic(name: string): Uint8Array | null {
  const path = resolve(PUBLIC_DIR, name.replace(/^\//, ''));
  return existsSync(path) ? new Uint8Array(readFileSync(path)) : null;
}

/** `<link rel="icon">` · `<link rel="apple-touch-icon">` 의 rel · href. */
function iconLinks(html: string): Array<{ rel: string; href: string }> {
  const links: Array<{ rel: string; href: string }> = [];
  for (const m of html.matchAll(/<link\b[^>]*>/gi)) {
    const rel = /\brel="([^"]*)"/i.exec(m[0])?.[1];
    const href = /\bhref="([^"]*)"/i.exec(m[0])?.[1];
    if (rel === undefined || href === undefined) continue;
    if (rel === 'icon' || rel === 'apple-touch-icon') links.push({ rel, href });
  }
  return links;
}

/** PNG 한 장의 문제 — 서명 · 청크 걷기 · 글자 청크 · IEND 뒤 바이트. */
function pngProblems(name: string, bytes: Uint8Array): string[] {
  if (bytes.length < 8 || PNG_SIGNATURE.some((v, i) => bytes[i] !== v)) return [`${name}: PNG 서명 아님`];
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const problems: string[] = [];
  let at = 8;
  while (at + 8 <= bytes.length) {
    const length = view.getUint32(at);
    const type = String.fromCharCode(...bytes.subarray(at + 4, at + 8));
    const end = at + 12 + length; // 길이 4 + 종류 4 + 자료 + CRC 4
    if (end > bytes.length) return [...problems, `${name}: ${type} 청크가 파일 끝을 넘음`];
    if (TEXT_CHUNKS.has(type)) problems.push(`${name}: 글자 청크 ${type}`);
    if (type === 'IEND') {
      if (end !== bytes.length) problems.push(`${name}: IEND 뒤 ${bytes.length - end} 바이트`);
      return problems;
    }
    at = end;
  }
  return [...problems, `${name}: IEND 없음`];
}

/** 링크 쪽 문제 — icon 둘 · apple-touch-icon 하나 · 각 href 의 파일이 `public/` 에 있음. */
function linkProblems(html: string, read: (name: string) => Uint8Array | null): string[] {
  const links = iconLinks(html);
  const problems: string[] = [];
  const icons = links.filter((l) => l.rel === 'icon').length;
  const touch = links.filter((l) => l.rel === 'apple-touch-icon').length;
  if (icons !== 2) problems.push(`icon 링크 ${icons}개(기대 2)`);
  if (touch !== 1) problems.push(`apple-touch-icon 링크 ${touch}개(기대 1)`);
  for (const l of links) if (read(l.href) === null) problems.push(`${l.href}: public/ 에 없음`);
  return problems;
}

/** 파비콘 메타 문제 전부 — 링크 쪽 + PNG 두 장. 빈 배열이면 통과. */
function faviconProblems(html: string, read: (name: string) => Uint8Array | null): string[] {
  const problems = linkProblems(html, read);
  for (const name of PNG_ICONS) {
    const bytes = read(name);
    if (bytes === null) problems.push(`${name}: public/ 에 없음`);
    else problems.push(...pngProblems(name, bytes));
  }
  return problems;
}

/** 청크 하나를 만든다(CRC 는 검사 함수가 안 보므로 0). */
function chunk(type: string, data: number[]): number[] {
  const len = data.length;
  return [(len >>> 24) & 0xff, (len >>> 16) & 0xff, (len >>> 8) & 0xff, len & 0xff, ...Array.from(type, (c) => c.charCodeAt(0)), ...data, 0, 0, 0, 0];
}

describe('파비콘 메타 — index.html ↔ public/', () => {
  // TA-R29-26 · AC-R29-37 — 아이콘 링크 셋이 모두 public/ 의 실물 파일을 가리킨다.
  it('linksEveryIconToAFileInPublic', () => {
    // 전제: 경로가 실물을 가리킨다(작업 폴더가 apilens-ui 루트가 아니면 여기서 멈춘다).
    expect(existsSync(HTML_PATH)).toBe(true);
    expect(existsSync(PUBLIC_DIR)).toBe(true);
    const html = readFileSync(HTML_PATH, 'utf-8');
    const links = iconLinks(html);
    expect(links).toHaveLength(3); // 전제: 링크 셋을 실제로 읽었다(빈 그물 0)
    for (const l of links) expect(readPublic(l.href), l.href).not.toBeNull();
    expect(linkProblems(html, readPublic)).toEqual([]);
  });

  // TA-R29-27 · AC-R29-37 — PNG 두 장에 글자 청크(tEXt · zTXt · iTXt) 0 · IEND 가 파일 끝.
  it('keepsThePngIconsFreeOfTextChunksAndTrailingBytes', () => {
    for (const name of PNG_ICONS) {
      const bytes = readPublic(name);
      expect(bytes, name).not.toBeNull(); // 전제: 파일이 있다
      expect(pngProblems(name, bytes!)).toEqual([]);
    }
    // 판정력: 같은 검사가 글자 청크와 끝 뒤 바이트를 실제로 잡는다(사본에 끼워 넣어 본다 · 파일 무접촉).
    const real = Array.from(readPublic('favicon.png')!);
    const iendAt = real.length - 12;
    const dirty = new Uint8Array([
      ...real.slice(0, iendAt),
      ...chunk('tEXt', Array.from('Software\0editor', (c) => c.charCodeAt(0))),
      ...real.slice(iendAt),
      0x00,
    ]);
    expect(pngProblems('favicon.png', dirty)).toEqual(['favicon.png: 글자 청크 tEXt', 'favicon.png: IEND 뒤 1 바이트']);
  });

  // TA-R29-28 · AC-R29-37 — 링크 하나가 빠진 html 이면 빨강(문제 ≥ 1). `index.html` 파일은 안 고친다.
  it('failsWhenALinkIsMissingFromTheHtml', () => {
    const html = readFileSync(HTML_PATH, 'utf-8');
    expect(faviconProblems(html, readPublic)).toEqual([]); // 전제: 원본은 통과
    const withoutTouchIcon = html.replace(/<link\b[^>]*rel="apple-touch-icon"[^>]*>/i, '');
    expect(withoutTouchIcon).not.toBe(html); // 전제: 사본에서 링크 하나가 실제로 빠졌다
    expect(faviconProblems(withoutTouchIcon, readPublic).length).toBeGreaterThanOrEqual(1);
    const withMissingFile = html.replace('href="/favicon.svg"', 'href="/favicon-missing.svg"');
    expect(faviconProblems(withMissingFile, readPublic)).toContain('/favicon-missing.svg: public/ 에 없음');
  });
});
