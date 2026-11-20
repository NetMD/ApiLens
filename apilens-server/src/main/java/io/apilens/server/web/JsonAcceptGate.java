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
package io.apilens.server.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers 406 before the handler runs when the {@code Accept} header leaves no representation the matched
 * mapping can actually write.
 *
 * <p>The mapping-level {@code produces} condition ignores quality values and charset parameters, so a request
 * such as {@code Accept: application/json;q=0} or {@code Accept: application/json;charset=ISO-8859-1} used to
 * reach the handler (and change data) and only failed, if at all, when the response was written. This gate
 * re-judges the header in one place, after authentication and mapping, before the handler:
 * <ol>
 *   <li>entries with {@code q=0} are dropped; if nothing usable is left the request ends with 406 (an empty
 *       list after filtering is not read as {@code *}{@code /*});</li>
 *   <li>an entry counts only if it is compatible with one of the mapping's producible types and, for JSON
 *       types, carries no charset or one of {@link #WRITABLE_JSON_CHARSETS};</li>
 *   <li>the media type Spring MVC will pick for writing is computed with the same steps Spring uses, and
 *       must itself be writable — a higher-quality entry with an unwritable charset would otherwise win.</li>
 * </ol>
 * A missing or blank {@code Accept} header passes (read as {@code *}{@code /*}, like Spring). The 406 is raised
 * as {@link HttpMediaTypeNotAcceptableException} with the mapping's producible types — the same exception the
 * mapping itself raises — so the status, log line and error body stay those of the existing 406.
 */
public final class JsonAcceptGate implements HandlerInterceptor {

    // [Phase R33] R33/AC-R33-06-2 재작업 1 — 통과 문자셋 = 범위 잠금 문면 그대로(UTF 다섯 · 「만」). 사용자 명시 비협상 결정
    //   (UQ-R33-3 처리 전 406). 공개 문서 docs/api.md 406 줄 「Requests whose Accept excludes JSON end with 406 before
    //   processing; nothing is changed.」 인용.
    //   ★한계 그 자리에: 쓰기 쪽 Jackson 변환기는 US-ASCII 도 받는다(UTF-8 로 씀) — 이 목록 밖이라 여기서는 406 이다.
    //     틀리는 방향이 안전한 쪽(처리 전 거절 · 바뀐 것 0)이라 문면을 따랐다.
    static final Set<String> WRITABLE_JSON_CHARSETS = Set.of("UTF-8", "UTF-16BE", "UTF-16LE", "UTF-32BE", "UTF-32LE");

    private static final MediaType JSON_SUFFIX = MediaType.parseMediaType(ApiMediaTypes.JSON_SUFFIX_VALUE);

    // Spring MVC 쓰기 단계가 와일드카드로 끝난 형식을 octet-stream 으로 바꾸는 두 꼴(같은 셈을 하려고 그대로 둔다).
    private static final List<MediaType> ALL_APPLICATION_MEDIA_TYPES =
            List.of(MediaType.ALL, new MediaType("application"));

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) throws HttpMediaTypeNotAcceptableException {
        // 오류 재전달(/error) · 정적 자원 · 뷰 전달은 판정 밖 — 처리기 메서드로 온 첫 요청만 본다.
        if (request.getDispatcherType() != DispatcherType.REQUEST || !(handler instanceof HandlerMethod)) {
            return true;
        }
        // 매핑의 produces 가 정한 형식(매핑 단계가 요청 속성에 남긴 것). produces 가 없는 매핑은 판정 밖이다.
        List<MediaType> producible = new ArrayList<>();
        if (request.getAttribute(HandlerMapping.PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE) instanceof Set<?> set) {
            for (Object o : set) {
                if (o instanceof MediaType type) {
                    producible.add(type);
                }
            }
        }
        if (producible.isEmpty()) {
            return true;
        }
        List<String> accept = Collections.list(request.getHeaders(HttpHeaders.ACCEPT));
        if (!acceptsWritable(accept, producible)) {
            throw new HttpMediaTypeNotAcceptableException(producible);
        }
        return true;
    }

    /**
     * Returns whether the given {@code Accept} header values leave at least one representation of the producible
     * types that can be written, using the three rules described on the class.
     *
     * @param acceptValues raw {@code Accept} header values (may be empty)
     * @param producible   media types the matched mapping declares in {@code produces}
     * @return {@code true} if the handler may run
     */
    public static boolean acceptsWritable(List<String> acceptValues, List<MediaType> producible) {
        List<MediaType> accepted;
        try {
            accepted = new ArrayList<>(MediaType.parseMediaTypes(acceptValues));
            if (accepted.isEmpty()) {
                return true; // 머리 없음 · 빈 값 = */* (Spring 과 같은 읽기)
            }
            MimeTypeUtils.sortBySpecificity(accepted);
        } catch (IllegalArgumentException ex) {
            return false; // 읽을 수 없는 꼴 · 항목 과다 — 매핑 단계도 406 으로 끝낸다
        }

        // ① q=0 을 빼고 남은 항목 가운데 쓸 수 있는 것이 하나라도 있나 — 걸러서 비면 406(빈 목록을 */* 로 읽지 않는다).
        boolean usable = false;
        for (MediaType entry : accepted) {
            if (entry.getQualityValue() <= 0) {
                continue;
            }
            for (MediaType type : producible) {
                if (entry.isCompatibleWith(type) && (!isJson(type) || charsetWritable(entry))) {
                    usable = true;
                    break;
                }
            }
        }
        if (!usable) {
            return false;
        }

        // ② 쓰기 단계가 실제로 고를 형식도 쓸 수 있어야 한다 — 높은 q 의 못 쓰는 문자셋 항목이 이기면 처리 뒤 쓰기 실패(500)다.
        MediaType selected = selectForWriting(accepted, producible);
        if (selected == null || selected.getQualityValue() <= 0) {
            return false;
        }
        boolean producibleMatch = false;
        for (MediaType type : producible) {
            if (type.isCompatibleWith(selected)) {
                producibleMatch = true;
                break;
            }
        }
        return producibleMatch && (!isJson(selected) || charsetWritable(selected));
    }

    /**
     * Picks the media type the way Spring MVC's message-converter return value handling does: the more specific of
     * each compatible accept/producible pair (with the accept entry's quality), sorted by quality then specificity,
     * first concrete type wins.
     */
    static MediaType selectForWriting(List<MediaType> accepted, List<MediaType> producible) {
        Set<MediaType> compatible = new LinkedHashSet<>();
        for (MediaType entry : accepted) {
            for (MediaType type : producible) {
                if (entry.isCompatibleWith(type)) {
                    MediaType typeToUse = type.copyQualityValue(entry);
                    compatible.add(entry.isLessSpecific(typeToUse) ? typeToUse : entry);
                }
            }
        }
        List<MediaType> sorted = new ArrayList<>(compatible);
        MimeTypeUtils.sortBySpecificity(sorted);
        for (MediaType type : sorted) {
            if (type.isConcrete()) {
                return type;
            }
            if (type.isPresentIn(ALL_APPLICATION_MEDIA_TYPES)) {
                return MediaType.APPLICATION_OCTET_STREAM;
            }
        }
        return null;
    }

    /** JSON 형식인가 — {@code application/json} 또는 {@code +json} 접미 형식. */
    static boolean isJson(MediaType type) {
        return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(type) || JSON_SUFFIX.includes(type);
    }

    /** 문자셋이 없거나 통과 목록 안인가. */
    static boolean charsetWritable(MediaType type) {
        Charset charset = type.getCharset();
        return charset == null || WRITABLE_JSON_CHARSETS.contains(charset.name());
    }
}
