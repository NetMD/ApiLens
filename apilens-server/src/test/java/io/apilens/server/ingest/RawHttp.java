/*
 * Copyright 2026 ApiLens Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.apilens.server.ingest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Raw one-shot HTTP/1.1 client for container-level content-negotiation tests.
 *
 * <p>[Phase R33] R33/AC-R33-07-6 — {@code YamlIngressIntegrationTest} 의 원문 송신 도우미를 옮긴 것이다(설계 부록 B
 * B1 · 본편 §6.1 K-R33-1). 두 시험 클래스({@code YamlIngressIntegrationTest} · {@code MutatingAcceptGateIntegrationTest})가
 * 함께 쓴다. 옛 본문과 다른 자리는 둘뿐이다 — 포트를 인자로 받는다 · 덧머리 줄(가변 인자)을 {@code Accept} 앞에 쓴다.
 */
final class RawHttp {

    private RawHttp() {
    }

    /** 응답 하나 — 상태 · 응답 형식 머리(없으면 빈 글자) · 본문(chunked 면 푼 글자). */
    record Response(int status, String contentType, String body) {
        @Override
        public String toString() {
            return status + " " + contentType + " " + head(body);
        }
    }

    /**
     * 원문 그대로 한 요청을 써 보낸다 — {@code Content-Type} · {@code Accept} 를 글자 그대로 싣는다(값이 null 이면 그 머리를 안
     * 보낸다). 덧머리 줄은 {@code Accept} 앞에 한 줄씩 쓴다. 대기는 소켓 읽기 제한 시간뿐이다.
     */
    static Response send(int port, String method, String path, String contentType, String accept, String body,
                         String... extraHeaderLines) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        head.append("Host: 127.0.0.1:").append(port).append("\r\n");
        head.append("Connection: close\r\n");
        for (String line : extraHeaderLines) {
            head.append(line).append("\r\n");
        }
        if (accept != null) {
            head.append("Accept: ").append(accept).append("\r\n");
        }
        if (contentType != null) {
            head.append("Content-Type: ").append(contentType).append("\r\n");
        }
        if (bytes.length > 0 || "POST".equals(method)) {
            head.append("Content-Length: ").append(bytes.length).append("\r\n");
        }
        head.append("\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bytes);
            out.flush();
            return parse(readAll(socket.getInputStream()));
        }
    }

    static String head(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** 상태 줄 · 응답 형식 머리 · 본문을 가른다. 본문이 chunked 면 풀어 쓴다(Connection: close 라 끝은 EOF). */
    private static Response parse(byte[] raw) {
        String all = new String(raw, StandardCharsets.ISO_8859_1);
        int headEnd = all.indexOf("\r\n\r\n");
        String head = headEnd < 0 ? all : all.substring(0, headEnd);
        String[] headLines = head.split("\r\n");
        String[] parts = headLines[0].split(" ");
        int status = parts.length > 1 ? Integer.parseInt(parts[1]) : -1;
        String contentType = "";
        boolean chunked = false;
        for (String line : headLines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("content-type:")) {
                contentType = line.substring("content-type:".length()).trim();
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }
        byte[] bodyBytes = headEnd < 0 ? new byte[0] : Arrays.copyOfRange(raw, headEnd + 4, raw.length);
        if (chunked) {
            bodyBytes = dechunk(bodyBytes);
        }
        return new Response(status, contentType, new String(bodyBytes, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < in.length) {
            int lineEnd = indexOfCrlf(in, i);
            if (lineEnd < 0) {
                break;
            }
            String sizeText = new String(in, i, lineEnd - i, StandardCharsets.ISO_8859_1).trim();
            int semi = sizeText.indexOf(';');
            int size = Integer.parseInt(semi >= 0 ? sizeText.substring(0, semi) : sizeText, 16);
            if (size == 0) {
                break;
            }
            int start = lineEnd + 2;
            out.write(in, start, Math.min(size, in.length - start));
            i = start + size + 2;
        }
        return out.toByteArray();
    }

    private static int indexOfCrlf(byte[] in, int from) {
        for (int i = from; i + 1 < in.length; i++) {
            if (in[i] == '\r' && in[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
