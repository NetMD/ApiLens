// [Phase R19] UI 표시 버전 라벨과 package.json version 일치 강제.
//
// R18 회귀 재발 차단: package.json 만 bump 하고 src/lib/version.ts(APP_VERSION)를 놓쳐
// 릴리스 직전 사용자 지적으로 회수한 적이 있다. 그 회귀는 사람이 눈으로 막을 일이 아니라
// 이 테스트 한 줄이 막을 일이다 (표시값 단일 출처화의 부분 충족 — 빌드 타임 주입은 vite.config
// 회귀 표면을 열어야 해서 이번 범위 밖, backlog).
//
// 검증 의무 (정방향 동사):
//   matchesPackageJsonMajorMinor — APP_VERSION == `v{major}.{minor}`
//   [R30 · AC-R30-40 · UD-R30-12] 같은 시험 안에 라벨 정확 값 단언 1개를 더한다 — 앞 두 자리 대조는 그대로.
//     시험 수는 늘리지 않는다(기획 AC-R30-09 판별식 · 화면 555 고정). 다음 판올림은 이 기대값도 같이 올린다.
import { describe, expect, it } from 'vitest';
import pkg from '../../package.json';
import { APP_VERSION } from '../lib/version';

describe('APP_VERSION — 표시 라벨 일치', () => {
  it('matchesPackageJsonMajorMinor — APP_VERSION 은 package.json version 의 앞 두 자리와 일치한다', () => {
    const [major, minor] = pkg.version.split('.');
    expect(APP_VERSION).toBe(`v${major}.${minor}`);
    // [R30 · AC-R30-40 · UD-R30-12] 화면 라벨 `v0.10`(처음으로 다섯 글자) — 정확 값 고정.
    expect(APP_VERSION).toBe('v0.10');
  });
});
