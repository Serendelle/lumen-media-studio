package com.houqing.tools.video_compressor_backend.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.media.handler.VideoCompressHandler;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates a model-proposed plan with exactly the same rules Professional mode uses (each handler's validate()),
 * before the user is ever asked to confirm it. Model output is untrusted input: tool names are allow-listed,
 * inputs must be the owner's files or earlier steps, and params are bounded. The result is a normalised plan.
 */
@Component
public class PlanValidator {
    static final int MAX_STEPS = 8;
    static final Set<String> TOOLS = Set.of("video.compress", "video.convert", "video.watermark", "audio.extract",
            "image.process", "image.removebg", "pdf.process", "metadata.scrub", "metadata.modify", "document.convert");
    /**
     * Operations an agent must never put in a plan, whatever it was told: they act on a whole project or delete
     * data, so only the user's own click on the page may trigger them.
     */
    static final Map<String, String> FORBIDDEN = Map.of(
            "spec.deliver", "生成交付包只能由你在「材料规范官」页面确认后执行",
            "spec.delete", "删除项目只能由你在页面上二次确认后执行",
            "file.delete", "删除文件只能由你在「我的文件」中确认后执行",
            "flow.publish", "发布操作只能由你在页面上确认后执行",
            "flow.delete", "删除操作只能由你在页面上二次确认后执行");

    private final TaskEngine engine;
    private final StorageService storage;
    private final MediaProbe probe;
    private final ObjectMapper mapper;

    public PlanValidator(TaskEngine engine, StorageService storage, MediaProbe probe, ObjectMapper mapper) {
        this.engine = engine;
        this.storage = storage;
        this.probe = probe;
        this.mapper = mapper;
    }

    public record Outcome(boolean ok, ObjectNode plan, List<String> errors) {}

    public Outcome validate(long ownerId, JsonNode proposed) {
        List<String> errors = new ArrayList<>();
        ArrayNode steps = proposed.path("steps").isArray() ? (ArrayNode) proposed.path("steps") : mapper.createArrayNode();
        if (steps.isEmpty()) errors.add("计划没有任何步骤");
        if (steps.size() > MAX_STEPS) errors.add("步骤过多（最多 " + MAX_STEPS + " 步）");

        ObjectNode plan = mapper.createObjectNode();
        plan.put("summary", clip(proposed.path("summary").asText(""), 300));
        ArrayNode out = plan.putArray("steps");
        ArrayNode warnings = plan.putArray("warnings");
        for (JsonNode w : proposed.path("warnings")) if (warnings.size() < 6) warnings.add(clip(w.asText(), 200));

        // virtual outputs of earlier steps, used to validate later steps that consume them
        Map<Integer, MediaFile> virtual = new HashMap<>();
        int expectedId = 1;
        for (JsonNode s : steps) {
            if (out.size() >= MAX_STEPS) break;
            int id = s.path("id").asInt(-1);
            String label = "步骤 " + (id > 0 ? id : expectedId);
            if (id != expectedId) {
                errors.add(label + "：id 必须从 1 开始连续编号");
                id = expectedId;
            }
            expectedId++;
            String tool = s.path("tool").asText("");
            if (FORBIDDEN.containsKey(tool)) {
                errors.add(label + "：" + FORBIDDEN.get(tool) + "，不能放进 AI 计划");
                continue;
            }
            if (!TOOLS.contains(tool)) {
                errors.add(label + "：不支持的 tool「" + clip(tool, 40) + "」");
                continue;
            }
            JsonNode params = s.path("params").isObject() ? s.path("params") : mapper.createObjectNode();
            if (params.toString().length() > 2000) {
                errors.add(label + "：参数过大");
                continue;
            }
            List<String> refs = new ArrayList<>();
            if (s.path("inputs").isArray() && !s.path("inputs").isEmpty()) s.path("inputs").forEach(x -> refs.add(x.asText()));
            else if (s.hasNonNull("input")) refs.add(s.path("input").asText());
            if (refs.isEmpty()) {
                errors.add(label + "：缺少 input");
                continue;
            }

            List<MediaFile> inputs = new ArrayList<>();
            boolean refOk = true;
            for (String ref : refs) {
                if (ref.startsWith("step:")) {
                    int dep;
                    try {
                        dep = Integer.parseInt(ref.substring(5));
                    } catch (NumberFormatException e) {
                        dep = -1;
                    }
                    MediaFile v = virtual.get(dep);
                    if (dep <= 0 || dep >= id || v == null) {
                        errors.add(label + "：input「" + clip(ref, 20) + "」必须引用之前的有效步骤");
                        refOk = false;
                    } else {
                        inputs.add(v);
                    }
                } else {
                    Optional<MediaFile> f = storage.find(ownerId, ref);
                    if (f.isEmpty()) {
                        errors.add(label + "：fileId「" + clip(ref, 40) + "」不存在或不属于当前用户");
                        refOk = false;
                    } else {
                        inputs.add(f.get());
                    }
                }
            }
            if (!refOk) continue;

            try {
                engine.handler(tool).validate(params, inputs);
            } catch (ApiException e) {
                errors.add(label + "（" + tool + "）：" + e.getMessage());
                continue;
            }

            ObjectNode step = out.addObject();
            step.put("id", id);
            step.put("tool", tool);
            step.put("title", clip(s.path("title").asText(tool), 80));
            ArrayNode in = step.putArray("inputs");
            refs.forEach(in::add);
            step.set("params", params);
            step.put("rationale", clip(s.path("rationale").asText(""), 240));
            String impact = s.path("qualityImpact").asText("none");
            step.put("qualityImpact", Set.of("none", "low", "medium", "high").contains(impact) ? impact : "medium");

            // the size target must be really achievable for files we can probe now
            if (tool.equals("video.compress") && "size".equals(params.path("mode").asText()) && !inputs.get(0).getId().startsWith("virtual:")) {
                CompressionPlanner.Plan est = VideoCompressHandler.estimate(probe.probe(storage.pathOf(inputs.get(0))), params);
                step.set("estimate", mapper.valueToTree(est));
                if (!est.feasible()) {
                    errors.add(label + "：目标体积不可实现（" + est.reason() + "）。可选建议：" + est.suggestions().stream()
                            .map(CompressionPlanner.Suggestion::note).toList());
                }
            }
            virtual.put(id, virtualOutput(id, tool, params, inputs));
        }
        return new Outcome(errors.isEmpty(), plan, errors);
    }

    /** Describes what a step will produce, so the next step's validate() sees the right kind and format. */
    private MediaFile virtualOutput(int id, String tool, JsonNode p, List<MediaFile> inputs) {
        MediaFile first = inputs.get(0);
        String[] kindExt = switch (tool) {
            case "video.compress", "video.watermark" -> new String[]{"VIDEO", "mp4"};
            case "video.convert" -> p.path("targetFormat").asText("mp4").equals("gif")
                    ? new String[]{"IMAGE", "gif"} : new String[]{"VIDEO", p.path("targetFormat").asText("mp4")};
            case "audio.extract" -> new String[]{"AUDIO", p.path("format").asText("mp3")};
            case "image.removebg" -> new String[]{"IMAGE", "png"};
            case "image.process" -> inputs.size() > 1 ? new String[]{"ARCHIVE", "zip"}
                    : new String[]{"IMAGE", com.houqing.tools.video_compressor_backend.media.handler.ImageProcessHandlerAccess.outputExt(p, first)};
            case "document.convert" -> new String[]{"PDF", "pdf"};
            case "pdf.process" -> "images-to-pdf".equals(p.path("tool").asText()) ? new String[]{"PDF", "pdf"} : new String[]{"ARCHIVE", "zip"};
            default -> new String[]{first.getMediaType(), StorageService.extensionOf(first.getStoredName())};
        };
        MediaFile v = new MediaFile();
        v.setId("virtual:" + id);
        v.setMediaType(kindExt[0]);
        v.setStoredName("step" + id + "." + kindExt[1]);
        v.setOriginalName(StorageService.baseName(first.getOriginalName()) + "." + kindExt[1]);
        v.setMimeType("application/octet-stream");
        return v;
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\x00-\\x1f\\x7f]", " ").trim();
        return t.length() > max ? t.substring(0, max) + "…" : t;
    }
}
