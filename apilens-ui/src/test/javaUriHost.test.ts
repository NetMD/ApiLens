// [2026-09-24] 화면 주소 검사가 서버(java.net.URI.getHost)와 같은 판정을 내는지.
// 기대값은 JDK 21(openjdk 21.0.12.1) 의 new URI(u).getHost() 로 잰 값이다 — 추정으로 채우지 말 것.
import { describe, expect, it } from 'vitest';
import { hostOfLikeJavaUri, portProblemLikeJavaUri } from '../lib/javaUriHost';

describe('hostOfLikeJavaUri — Java URI.getHost 와 같은 판정', () => {
  it.each([
    'http://',
    'https://',
    'http:///foo',
    'http://foo_bar:8765',
    'http://foo.123:8765',
    'http://256.1.1.1:8765',
    'http://1.2.3:8765',
    'http://-bad:8765',
    'http://bad-:8765',
    'http://한국.com:8765',
    'http://my host:8765',
    'http://host:8765/a b',
    'http://[::1',
    'http://host:abc',
    'http://user@@host:8765',
    'http://ho%73t',
    'http://host:99999999999',
    'http://host:2147483648',
    'http://[fe80::1%25en0%25x]:8765',
    'http://[1::2::3]:8765',
    'http://[fe80::1%25en-0]:8765',
    'http://[::1]x:8765',
    'http://user@host@x:1',
    'http://us[er@host',
    'http://host:8765/a%zz',
    'http://[1:2:3:4:5:6:7:8::]:1',
    'http://[1:2:3:4:5:6:7]:1',
    'http://[::1.2.3.256]:1',
    'http://[12345::1]:1',
    'http://[fe80::1%]:1',
    'http://host:-1',
    'http://ho~st:1',
    'http://[fe80::1%25en~0]:1',
    'http://host/a[b',
    'http://host/a]b',
    'http://host?a%zz',
    'http://[::1.2.3.4:5]:1',
    'http://[1.2.3.4::1]:1',
    'http://192.168.1.256',
    'http://host#foo#bar',
    'http://host?x=1#foo#bar',
    'http://host:12a',
    'http://host::8765',
    'http://[::1]:abc',
    'http://1.2.3.0256:1',
    'http://1.2.3.99999999999:1',
    'http://99999999999.1.1.1:1',
    'http://[::1.2.3.0256]:1',
    'http://1.2.3.4.:1',
    'http://1.2.3.4.5:1',
  ])('호스트 없음으로 본다: %s', (url) => {
    expect(hostOfLikeJavaUri(url)).toBeNull();
  });

  it.each([
    ['http://your-host:8765', 'your-host'],
    ['http://192.168.1.39:8765', '192.168.1.39'],
    ['http://[::1]:8765', '[::1]'],
    ['https://apilens.example.com', 'apilens.example.com'],
    ['http://localhost:8765/', 'localhost'],
    ['http://host.:8765', 'host.'],
    ['http://user@host:8765', 'host'],
    ['http://host:', 'host'],
    ['http://123abc:8765', '123abc'],
    ['http://[fe80::1%25en0]:8765', '[fe80::1%25en0]'],
    ['http://[fe80::1%en0]:8765', '[fe80::1%en0]'],
    ['http://host:2147483647', 'host'],
    ['http://host:65536', 'host'],
    ['http://host:0', 'host'],
    ['http://host:65535', 'host'],
    ['http://EXAMPLE.COM:8765', 'EXAMPLE.COM'],
    ['http://user%3Apw@host', 'host'],
    [`http://${'a'.repeat(64)}.com:8765`, `${'a'.repeat(64)}.com`],
    ['http://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:8765', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'],
    ['http://host:8765/path?x=1#f', 'host'],
    ['http://[::ffff:192.168.1.1]:8765', '[::ffff:192.168.1.1]'],
    ['http://[fe80::1%25]:8765', '[fe80::1%25]'],
    ['http://[fe80::1%251]:8765', '[fe80::1%251]'],
    ['http://host:8765@x', 'x'],
    ['http://us/er@host', 'us'],
    ['http://[::1]', '[::1]'],
    ['http://[1:2:3:4:5:6:7::]:1', '[1:2:3:4:5:6:7::]'],
    ['http://[1:2:3:4:5:6:7:8]:1', '[1:2:3:4:5:6:7:8]'],
    ['http://[::1.2.3.4]:1', '[::1.2.3.4]'],
    ['http://[::]:1', '[::]'],
    ['http://[fe80::1%25en_0]:1', '[fe80::1%25en_0]'],
    ['http://user;x=1@host', 'host'],
    ['http://host?q', 'host'],
    ['http://host#f', 'host'],
    ['http://[fe80::1%25en.0]:1', '[fe80::1%25en.0]'],
    ['http://host?a]b', 'host'],
    ['http://host/%41', 'host'],
    ['http://[::1]:8765/p', '[::1]'],
    ['http://user:pw@host:1', 'host'],
    ['http://host#a[b', 'host'],
    ['http://[::1]:', '[::1]'],
    ['http://host:0000000000000000008765', 'host'],
    ['http://[FE80::1]:1', '[FE80::1]'],
    ['http://192.168.001.039:8765', '192.168.001.039'],
    ['http://001.002.003.004:8765', '001.002.003.004'],
    ['http://0001.2.3.4:1', '0001.2.3.4'],
    ['http://host/p#a', 'host'],
    ['http://1.2.3.0004:1', '1.2.3.0004'],
    ['http://[::001.2.3.4]:1', '[::001.2.3.4]'],
    ['http://[::1.2.3.0004]:1', '[::1.2.3.0004]'],
    ['http://00000000000000000001.2.3.4:1', '00000000000000000001.2.3.4'],
  ])('호스트를 꺼낸다: %s → %s', (url, host) => {
    expect(hostOfLikeJavaUri(url)).toBe(host);
  });
});

// 호스트는 맞는데 포트만 틀린 경우를 따로 가려 화면이 고칠 자리를 짚게 한다.
describe('portProblemLikeJavaUri — 포트만 틀린 주소 가리기', () => {
  it.each(['http://host:abc', 'http://host:12a', 'http://host:99999999999', 'http://[::1]:abc'])(
    '포트 문제로 본다: %s',
    (url) => {
      expect(portProblemLikeJavaUri(url)).toBe(true);
    },
  );

  it.each(['http://host:8765', 'http://host:', 'http://', 'http://[::1]', 'http://foo_bar:abc'])(
    '포트 문제로 보지 않는다: %s',
    (url) => {
      expect(portProblemLikeJavaUri(url)).toBe(false);
    },
  );
});
