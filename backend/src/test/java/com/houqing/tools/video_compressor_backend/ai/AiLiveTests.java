package com.houqing.tools.video_compressor_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.Tool;
import com.houqing.tools.video_compressor_backend.support.ApiTestBase;
import com.houqing.tools.video_compressor_backend.support.TestMedia;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real DeepSeek, real processing, no doubles. Opt-in because it spends API credit:
 *   mvn test -Dtest=AiLiveTests -Dai.live=true      (needs DEEPSEEK_API_KEY in the environment or .env)
 */
@EnabledIfSystemProperty(named = "ai.live", matches = "true")
class AiLiveTests extends ApiTestBase {

    @Test
    void naturalLanguageRequestIsPlannedConfirmedExecutedAndVerified() throws Exception {
        TestMedia.assumeTools(Tool.FFMPEG, Tool.FFPROBE, Tool.EXIFTOOL);
        User u = register();
        String file = json(upload("/api/files/upload", TestMedia.get("demo.mp4"), "旅行 vlog.mp4", u)).path("fileId").asText();
        String id = json(postJson("/api/ai/conversations", Map.of(), u)).path("id").asText();
        postJson("/api/ai/conversations/" + id + "/messages", Map.of(
                "text", "帮我把这个视频压缩到 2MB 以内，尽量保证画质，再把音频提取成 MP3，最后清除里面可能泄露位置的信息。",
                "fileIds", List.of(file)), u);

        JsonNode c = wait(id, u, Set.of("AWAITING_CONFIRMATION", "AWAITING_INPUT", "FAILED"), 180_000);
        assertThat(c.path("state").asText()).isEqualTo("AWAITING_CONFIRMATION");
        JsonNode steps = c.path("plan").path("steps");
        List<String> tools = new java.util.ArrayList<>();
        steps.forEach(s -> tools.add(s.path("tool").asText()));
        assertThat(tools).contains("video.compress", "audio.extract", "metadata.scrub");

        postJson("/api/ai/conversations/" + id + "/confirm", Map.of("version", c.path("planVersion").asInt(), "approve", true), u);
        c = wait(id, u, Set.of("COMPLETED", "FAILED"), 600_000);
        assertThat(c.path("state").asText()).isEqualTo("COMPLETED");
        assertThat(c.path("context").path("review").path("allPassed").asBoolean()).isTrue();
    }

    private JsonNode wait(String id, User u, Set<String> states, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        JsonNode c;
        do {
            c = json(get("/api/ai/conversations/" + id, u)).path("conversation");
            if (states.contains(c.path("state").asText())) return c;
            Thread.sleep(500);
        } while (System.currentTimeMillis() < end);
        return c;
    }
}
