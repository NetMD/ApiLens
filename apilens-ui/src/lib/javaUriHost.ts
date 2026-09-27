// 설치 마법사 주소 검사용 — 서버 SetupService.hostOf 와 짝.
//
// [2026-09-24 RA-R26-12] Java URI.getHost 와 같은 판정. 서버는 java.net.URI 로 호스트를 꺼내고,
//   못 꺼내면 [완료]에서 400 을 돌려준다. 화면이 더 너그러우면(브라우저 URL 은 밑줄 호스트·"http:///foo"·
//   한글 도메인을 받아 준다) 운영자는 400 뒤에 「잠시 후 다시 시도」라는 틀린 안내를 보게 되고,
//   더 엄하면(브라우저 URL 은 zone 붙은 IPv6 를 거부한다) 서버가 받는 주소로 설치를 못 한다.
//   [R27/UA-R27-3] 포트 상한 65535 는 이제 서버(SetupService)도 본다 — 화면은 같은 상한을 같은 커밋에 넣었다.
//   그래서 브라우저 URL(WHATWG) 파서는 쓰지 않고, Java 규칙만으로 **원문**을 읽는다.
//   기대값은 JDK 21 의 URI.getHost 로 잰 값이다 (src/test/javaUriHost.test.ts).
//   Java URI 문법을 손으로 옮긴 것이라 81+ 벡터 밖에서 갈릴 수 있다 — 갈리면 서버 400 이 두 번째 그물이고,
//   [R27/FR-27-10] 그 400 사유는 이제 설치 화면이 입력 가까이 한 줄로 보인다(「잠시 후 다시 시도」 대신).

/** Java URI 가 주소 어디에 있든 거부하는 글자 (공백·따옴표·꺾쇠 등). */
const ILLEGAL_ANYWHERE = /[\s"<>\\^`{|}]/;
/** %-인코딩은 뒤에 16진수 두 자리. (IPv6 zone 의 `%` 는 따로 본다.) */
const BAD_ESCAPE = /%(?![0-9A-Fa-f]{2})/;
/** userinfo 에 올 수 있는 글자 — `@`·`[`·`/` 등은 안 된다. */
const USERINFO = /^([A-Za-z0-9\-_.!~*'();:&=+$,]|%[0-9A-Fa-f]{2})*$/;
/** 마디는 숫자 여러 자리 · 값 0~255 (앞의 0 허용 — Java 21 실측: "001.002.003.004"·"0001.2.3.4" 통과, "0256" 거부). */
function isJavaIpv4(s: string): boolean {
  const parts = s.split('.');
  return parts.length === 4 && parts.every((p) => /^\d+$/.test(p) && Number(p) <= 255);
}
const HEX16 = /^[0-9A-Fa-f]{1,4}$/;
const LABEL = /^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$/;
/** Java 는 포트를 Integer.parseInt 로 읽는다 — 성공하면 범위(65535)는 안 본다. */
const INT_MAX = 2147483647;
/**
 * [R27/UA-R27-3] 설치 주소 포트 상한 — 서버 `SetupService.SERVER_URL_PORT_MAX` 와 짝(같은 커밋).
 * 하한(:0)은 이번 결정 밖이라 서버·화면 모두 통과시킨다(RP-15 ①).
 */
export const PORT_MAX = 65_535;

function isJavaHostname(host: string): boolean {
  // 끝 점 하나는 허용 (Java 와 같음).
  const labels = (host.endsWith('.') ? host.slice(0, -1) : host).split('.');
  if (!labels.every((l) => LABEL.test(l))) return false;
  // Java: 라벨이 둘 이상이면 맨 오른쪽 라벨은 영문자로 시작해야 한다 ("foo.123", "256.1.1.1" 거부).
  return labels.length === 1 || /^[A-Za-z]/.test(labels.at(-1) ?? '');
}

/** `[` `]` 안쪽. 16진 묶음 최대 8(끝에 IPv4 가 오면 2묶음으로 셈), `::` 는 한 번 · 묶음 7 이하일 때만. */
function isJavaIpv6(inner: string): boolean {
  // zone: `%` 뒤 영숫자·`_`·`.` 하나 이상 (Java 21 실측 — `%25en0` 도 `%en0` 도 받는다).
  const zone = /%[A-Za-z0-9_.]+$/.exec(inner);
  const addr = zone === null ? inner : inner.slice(0, zone.index);
  if (addr.includes('%')) return false;
  const halves = addr.split('::');
  if (halves.length > 2) return false;
  const groupsOf = (part: string): string[] | null => {
    if (part === '') return [];
    const gs = part.split(':');
    const last = gs.at(-1) ?? '';
    if (last.includes('.')) {
      if (!isJavaIpv4(last)) return null;
      gs.splice(-1, 1, '0', '0'); // IPv4 꼬리 = 16진 묶음 2개
    }
    return gs.every((g) => HEX16.test(g)) ? gs : null;
  };
  const head = groupsOf(halves[0] ?? '');
  const tail = halves.length === 2 ? groupsOf(halves[1] ?? '') : [];
  if (head === null || tail === null) return false;
  // IPv4 꼬리는 맨 끝에만 올 수 있다.
  if (halves.length === 2 && (halves[0] ?? '').includes('.')) return false;
  const count = head.length + tail.length;
  return halves.length === 2 ? count <= 7 : count === 8;
}

/** port = Java getPort 가 돌려줄 값(포트 없음·빈 포트 = -1). 포트 숫자는 이 파서의 digits 에서만 읽는다. */
type Judgement = { host: string; port: number } | { host: null; reason: 'no-host' | 'bad-port' };
const NO_HOST: Judgement = { host: null, reason: 'no-host' };

function judgeLikeJavaUri(url: string): Judgement {
  const m = /^https?:\/\/([^/?#]*)([^?#]*)(.*)$/s.exec(url);
  if (m === null || ILLEGAL_ANYWHERE.test(url)) return NO_HOST;
  const authority = m[1] ?? '';
  const path = m[2] ?? '';
  const queryAndFragment = m[3] ?? '';
  // 경로에는 `[` `]` 가 못 온다 (질의·조각에는 온다 — Java 실측).
  if (/[[\]]/.test(path) || BAD_ESCAPE.test(path) || BAD_ESCAPE.test(queryAndFragment)) return NO_HOST;
  // 조각 표시 `#` 는 하나만 — 둘 이상이면 Java 는 주소 전체를 거부한다.
  if ((queryAndFragment.match(/#/g) ?? []).length > 1) return NO_HOST;

  // userinfo 는 `@` 하나로만 가른다 — `@` 가 둘 이상이면 Java 는 호스트를 못 꺼낸다.
  const at = authority.split('@');
  if (at.length > 2) return NO_HOST;
  const hostPort = at.length === 2 ? (at[1] ?? '') : authority;
  if (at.length === 2 && !USERINFO.test(at[0] ?? '')) return NO_HOST;

  let host: string;
  let rest: string;
  if (hostPort.startsWith('[')) {
    const close = hostPort.indexOf(']');
    if (close < 0) return NO_HOST;
    host = hostPort.slice(0, close + 1);
    rest = hostPort.slice(close + 1);
    if (!isJavaIpv6(host.slice(1, -1))) return NO_HOST;
  } else {
    const colon = hostPort.indexOf(':');
    host = colon < 0 ? hostPort : hostPort.slice(0, colon);
    rest = colon < 0 ? '' : hostPort.slice(colon);
    if (!(isJavaIpv4(host) || isJavaHostname(host))) return NO_HOST;
  }
  // 포트: 비었거나 `:숫자`. 숫자는 int 로 읽히면 Java getHost 는 통과 — 65535 상한은 이 판정이 아니라
  //   serverUrlProblemLikeServer 가 본다([R27] 서버 SetupService 가 getHost 뒤에 따로 보는 것과 같은 순서).
  //   호스트는 맞는데 포트만 틀리면 'bad-port' — 화면이 고칠 자리를 바로 짚게 한다.
  const port = /^(?::(.*))?$/s.exec(rest);
  if (port === null) return NO_HOST;
  const digits = port[1];
  if (digits !== undefined && digits !== '' && !(/^\d+$/.test(digits) && Number(digits) <= INT_MAX)) {
    return { host: null, reason: 'bad-port' };
  }
  return { host, port: digits === undefined || digits === '' ? -1 : Number(digits) };
}

/**
 * Returns the host the server-side {@code java.net.URI#getHost()} would extract from {@code url},
 * or {@code null} when it would find none. Mirrors {@code SetupService.hostOf}.
 */
export function hostOfLikeJavaUri(url: string): string | null {
  return judgeLikeJavaUri(url).host;
}

/** True when the host part is fine but the port is not something Java would read as an int. */
export function portProblemLikeJavaUri(url: string): boolean {
  const j = judgeLikeJavaUri(url);
  return j.host === null && j.reason === 'bad-port';
}

/**
 * [R27/RP-10] Why the server would reject {@code url} in setup: {@code 'host'} when
 * {@code URI#getHost()} finds no host, {@code 'port'} when the port cannot be read as an int or is
 * above {@link PORT_MAX}, {@code null} when it would accept it. The setup page picks its message
 * from this one function so the shipped path is the tested path.
 *
 * 한계: `:2147483648` 은 서버가 「호스트 없음」으로 거부하고 화면은 「포트」로 보인다 — 거부 여부만 같다(BL-09).
 */
export function serverUrlProblemLikeServer(url: string): 'host' | 'port' | null {
  const j = judgeLikeJavaUri(url);
  if (j.host === null) return j.reason === 'bad-port' ? 'port' : 'host';
  return j.port > PORT_MAX ? 'port' : null;
}
