// [R27/P-R27-4] 움직임 줄이기(prefers-reduced-motion: reduce) 설정을 새로고침 없이 따르는 훅.
//
// 화면에서 matchMedia 를 부르는 유일한 파일이다(G-R27-15). 구독은 마운트 때 걸고 언마운트 때 푼다.
import { useEffect, useState } from 'react';

const REDUCED_MOTION_QUERY = '(prefers-reduced-motion: reduce)';

function mediaQuery(): MediaQueryList | null {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return null;
  return window.matchMedia(REDUCED_MOTION_QUERY);
}

/** 운영자가 OS 에서 「동작 줄이기」를 켰으면 참. 설정을 바꾸면 따라간다. */
export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState<boolean>(() => mediaQuery()?.matches ?? false);

  useEffect(() => {
    const mql = mediaQuery();
    if (mql === null) return;
    const onChange = (e: MediaQueryListEvent): void => setReduced(e.matches);
    // 첫 렌더와 구독 사이에 설정이 바뀐 경우를 한 번 맞춘다.
    setReduced(mql.matches);
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, []);

  return reduced;
}
