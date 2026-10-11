package com.houqing.tools.video_compressor_backend.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Full application context over MockMvc with an isolated SQLite database and storage dir.
 * Everything below the HTTP layer is real: JWT, SQLite, file storage and the external tools.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ApiTestBase {

    protected static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files.createTempDirectory("vcb-test-");
            for (String sub : new String[]{"tmp", "storage", "work", "logs"}) Files.createDirectories(DATA_DIR.resolve(sub));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("app.data-dir", DATA_DIR::toString);
        r.add("app.security.jwt-secret", () -> "integration-test-secret");
        r.add("app.media.min-free-disk-mb", () -> "1");
        r.add("app.cleanup-interval-ms", () -> "86400000");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;

    protected record User(String username, String token, long id) {}

    protected User register() throws Exception {
        String name = "u_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode r = json(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", name, "password", "Passw0rd!123")))).andReturn());
        return new User(name, r.path("token").asText(), r.path("user").path("id").asLong());
    }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, User u) {
        return b.header("Authorization", "Bearer " + u.token());
    }

    protected MvcResult postJson(String url, Object body, User u) throws Exception {
        MockHttpServletRequestBuilder b = post(url).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        return mvc.perform(u == null ? b : auth(b, u)).andReturn();
    }

    protected MvcResult get(String url, User u) throws Exception {
        MockHttpServletRequestBuilder b = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url);
        return mvc.perform(u == null ? b : auth(b, u)).andReturn();
    }

    protected MvcResult upload(String url, Path file, String filename, User u) throws Exception {
        MockMultipartFile mf = new MockMultipartFile("file", filename, "application/octet-stream", Files.readAllBytes(file));
        return mvc.perform(auth(multipart(url).file(mf), u)).andReturn();
    }

    protected MvcResult uploadBytes(String url, byte[] bytes, String filename, User u) throws Exception {
        MockMultipartFile mf = new MockMultipartFile("file", filename, "application/octet-stream", bytes);
        return mvc.perform(auth(multipart(url).file(mf), u)).andReturn();
    }

    protected JsonNode json(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return s.isEmpty() ? mapper.createObjectNode() : mapper.readTree(s);
    }

    /** Polls the unified task API until a terminal state (real processing, no mocks). */
    protected JsonNode waitTask(String taskId, User u, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        JsonNode t;
        do {
            t = json(get("/api/tasks/" + taskId, u));
            String s = t.path("status").asText();
            if (s.equals("SUCCEEDED") || s.equals("FAILED") || s.equals("CANCELLED")) return t;
            Thread.sleep(200);
        } while (System.currentTimeMillis() < end);
        return t;
    }
}
