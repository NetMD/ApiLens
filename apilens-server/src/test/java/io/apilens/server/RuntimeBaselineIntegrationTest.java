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
package io.apilens.server;

import org.apache.catalina.util.ServerInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [Phase R30] R30/AC-R30-10/R30/AC-R30-27 — 해석된 실물 판과 Jackson 배선을 잰다(설계 DT-R30-04 · §5.4 · 검사망의 실패 게이트).
 *
 * <p>AC-R30-27 원문: "해석 버전 고정 시험 = 서버 단언 다섯(Boot 4.1 계열 · Tomcat 11.0 계열 · sqlite-jdbc 3.53.4.0 · 서버
 * Jackson 2 2.21.7 · Jackson 3 3.1 계열) + agent 단언 1(2.18.10)을 따로 · 실패 게이트" (비협상). agent 단언은 shadow jar 를
 * 직접 읽는 CI 단계가 맡는다(설계 DD-R30-12 · DT-R30-15) — 이 파일은 서버 다섯과 배선만이다.
 *
 * <p>AC-R30-10(보충) 원문: "Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper} 빈 1(마스킹 전용) · Jackson 3
 * {@code JsonMapper} 빈 1 · 서버 main 에 {@code @Primary} Jackson 빈 0" (비협상).
 *
 * <p>★값은 그 판의 <b>실물</b>에서 읽는다 — 판 상수 클래스 · 클래스가 든 jar 의 {@code META-INF/maven/…/pom.properties}.
 * 카탈로그 글자를 읽지 않는다(카탈로그는 「적은 값」이고 판정은 「해석 값」이다 · 설계 §0.1 낱말 정본).
 *
 * <p>★한계 그 자리에: 이 시험은 <b>시험 클래스패스</b>를 잰다. 배포 jar 의 판은 {@code BOOT-INF/lib} 목록으로 따로 본다
 * (설계 DK-R30-07 · 출고 관문). CLAUDE.md '아키텍처 핵심 원칙'(server jar = 단일 배포 산출물) 인용.
 */
@SpringBootTest
class RuntimeBaselineIntegrationTest {

    // application.yml 의 상대경로 jdbc:sqlite:apilens.db 가 작업 디렉토리를 오염시키므로 temp 파일로 갈음한다.
    private static final Path TEMP_DB;

    static {
        try {
            TEMP_DB = Files.createTempFile("apilens-runtime-baseline-it-", ".db");
            Files.deleteIfExists(TEMP_DB);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + TEMP_DB.toAbsolutePath()
                        + "?journal_mode=WAL&synchronous=NORMAL&busy_timeout=5000");
    }

    @AfterAll
    static void deleteTempDb() throws IOException {
        String base = TEMP_DB.toAbsolutePath().toString();
        Files.deleteIfExists(TEMP_DB);
        Files.deleteIfExists(Path.of(base + "-wal"));
        Files.deleteIfExists(Path.of(base + "-shm"));
    }

    @Autowired
    private ApplicationContext context;

    /** 서버 판 다섯 — 설계 DD-R30-02 판 고정 값(점 값). */
    @Test
    void resolvesTheTargetLibraryLines() throws Exception {
        assertEquals("4.1.2", SpringBootVersion.getVersion(), "Spring Boot 판");
        assertTrue(ServerInfo.getServerNumber().startsWith("11.0.27"),
                "Tomcat 판 — 실제 " + ServerInfo.getServerNumber());
        assertEquals("3.53.4.0", pomVersionOf(org.sqlite.JDBC.class, "org.xerial", "sqlite-jdbc"), "sqlite-jdbc 판");
        assertEquals("2.21.7", com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION.toString(),
                "서버 Jackson 2 판(jackson-2-bom)");
        assertEquals("3.1.7", tools.jackson.databind.cfg.PackageVersion.VERSION.toString(), "Jackson 3 판");
    }

    /** Jackson 빈 — Jackson 2 는 마스킹 전용 이름 빈 하나 · Jackson 3 JsonMapper 하나. */
    @Test
    void wiresOneMaskingJackson2MapperAndOneJsonMapper() {
        Map<String, com.fasterxml.jackson.databind.ObjectMapper> jackson2 =
                context.getBeansOfType(com.fasterxml.jackson.databind.ObjectMapper.class);
        assertEquals(1, jackson2.size(), "Jackson 2 ObjectMapper 빈 수 — 실제 " + jackson2.keySet());
        assertTrue(jackson2.containsKey("maskingObjectMapper"), "Jackson 2 빈 이름 = 마스킹 전용 — 실제 " + jackson2.keySet());
        Map<String, JsonMapper> jackson3 = context.getBeansOfType(JsonMapper.class);
        assertEquals(1, jackson3.size(), "Jackson 3 JsonMapper 빈 수 — 실제 " + jackson3.keySet());
    }

    /**
     * HTTP 변환기 — JSON 본문은 Jackson 3 변환기가 맡고 Jackson 2 JSON 변환기는 0.
     *
     * <p>★한계 그 자리에(설계와 다른 자리 · dev-backend Phase B 결정 요약 자진 신고): Spring Framework 7 은 Jackson 2 YAML
     * 모듈이 클래스패스에 있으면 YAML 변환기({@code MappingJackson2YamlHttpMessageConverter})를 기본으로 붙인다 — springdoc 3 이
     * 그 모듈을 끌어온다. 그래서 「클래스 이름에 MappingJackson2 0」(설계 §5.4 원문)은 4.1 에서 성립하지 않는다. 이 시험은
     * <b>JSON</b> 변환기만 단언하고 YAML 변환기의 처분(받음 / 뺌)은 설계 검토로 넘겼다.
     */
    @Test
    void servesJsonBodiesWithJackson3ConvertersOnly() {
        List<HttpMessageConverter<?>> converters =
                context.getBean(RequestMappingHandlerAdapter.class).getMessageConverters();
        long jackson2Json = converters.stream()
                .filter(c -> c.getClass().getName().equals(
                        "org.springframework.http.converter.json.MappingJackson2HttpMessageConverter"))
                .count();
        long jackson3Json = converters.stream().filter(c -> c.getClass().getName().contains("JacksonJson")).count();
        assertEquals(0, jackson2Json, "Jackson 2 JSON 변환기 0 — 실제 " + converters);
        assertTrue(jackson3Json >= 1, "Jackson 3 JSON 변환기 1 이상 — 실제 " + converters);
    }

    /** 클래스가 든 jar 의 Maven 메타(pom.properties)에서 판을 읽는다. */
    private static String pomVersionOf(Class<?> type, String groupId, String artifactId)
            throws IOException, URISyntaxException {
        Path jar = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry("META-INF/maven/" + groupId + "/" + artifactId + "/pom.properties");
            assertNotNull(entry, "pom.properties 가 jar 안에 있어야 한다 — " + jar);
            Properties props = new Properties();
            try (InputStream in = file.getInputStream(entry)) {
                props.load(in);
            }
            return props.getProperty("version");
        }
    }
}
