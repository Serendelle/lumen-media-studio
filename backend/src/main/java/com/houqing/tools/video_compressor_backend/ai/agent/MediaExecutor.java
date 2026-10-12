package com.houqing.tools.video_compressor_backend.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.media.TaskEntity;
import com.houqing.tools.video_compressor_backend.media.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Agent 03 — deterministic. Runs a confirmed plan step by step on the same TaskEngine Professional mode uses
 * (origin = AI), wiring "step:N" inputs to earlier outputs, streaming real task progress, skipping steps whose
 * dependency failed, and honouring cancellation. No language model is involved here by design.
 */
@Component
public class MediaExecutor {
    private final TaskEngine engine;
    private final ObjectMapper mapper;

    public MediaExecutor(TaskEngine engine, ObjectMapper mapper) {
        this.engine = engine;
        this.mapper = mapper;
    }

    /** Receives step updates: {stepId, status, taskId?, progress?, stage?, outputFileId?, message?}. */
    public interface Listener {
        void step(ObjectNode update);
    }

    public record StepResult(int stepId, String tool, String status, String taskId, String outputFileId,
                             JsonNode params, JsonNode result, String message) {}

    public Map<Integer, StepResult> run(long ownerId, String conversationId, int planVersion, JsonNode plan,
                                        Listener listener, BooleanSupplier cancelled, Set<String> runningTasks) {
        Map<Integer, StepResult> results = new LinkedHashMap<>();
        for (JsonNode step : plan.path("steps")) {
            int id = step.path("id").asInt();
            String tool = step.path("tool").asText();
            JsonNode params = step.path("params");
            if (cancelled.getAsBoolean()) {
                results.put(id, skip(id, tool, params, "会话已取消", listener));
                continue;
            }
            List<String> inputs = new ArrayList<>();
            String blocked = null;
            for (JsonNode ref : step.path("inputs")) {
                String r = ref.asText();
                if (r.startsWith("step:")) {
                    StepResult dep = results.get(Integer.parseInt(r.substring(5)));
                    if (dep == null || !"SUCCEEDED".equals(dep.status()) || dep.outputFileId() == null) {
                        blocked = "依赖的步骤 " + r.substring(5) + " 未成功";
                        break;
                    }
                    inputs.add(dep.outputFileId());
                } else {
                    inputs.add(r);
                }
            }
            if (blocked != null) {
                results.put(id, skip(id, tool, params, blocked, listener));
                continue;
            }
            results.put(id, runStep(ownerId, conversationId + ":" + planVersion + ":" + id, id, tool, params, inputs,
                    listener, cancelled, runningTasks));
        }
        return results;
    }

    /** Submits one task and follows it to a terminal state; also used by the reviewer for its single retry. */
    public StepResult runStep(long ownerId, String idemKey, int stepId, String tool, JsonNode params, List<String> inputs,
                              Listener listener, BooleanSupplier cancelled, Set<String> runningTasks) {
        TaskEntity t;
        try {
            t = engine.submit(ownerId, tool, params, inputs, "AI", "ai:" + idemKey);
        } catch (ApiException e) {
            ObjectNode u = update(stepId, "FAILED");
            u.put("message", e.getMessage());
            listener.step(u);
            return new StepResult(stepId, tool, "FAILED", null, null, params, null, e.getMessage());
        }
        runningTasks.add(t.getId());
        try {
            int lastProgress = -1;
            String lastStatus = null, lastStage = null;
            while (true) {
                if (cancelled.getAsBoolean() && !TaskStatus.valueOf(t.getStatus()).terminal()) {
                    try {
                        engine.cancel(ownerId, t.getId());
                    } catch (ApiException ignored) {
                        // already finished
                    }
                }
                t = engine.require(ownerId, t.getId());
                if (!t.getStatus().equals(lastStatus) || t.getProgress() != lastProgress || !Objects.equals(t.getStage(), lastStage)) {
                    lastStatus = t.getStatus();
                    lastProgress = t.getProgress();
                    lastStage = t.getStage();
                    ObjectNode u = update(stepId, t.getStatus());
                    u.put("taskId", t.getId());
                    u.put("progress", t.getProgress());
                    if (t.getStage() != null) u.put("stage", t.getStage());
                    if (TaskStatus.valueOf(t.getStatus()).terminal()) {
                        if (t.getOutputFile() != null) u.put("outputFileId", t.getOutputFile());
                        if (t.getMessage() != null) u.put("message", t.getMessage());
                        if (t.getErrorCode() != null) u.put("errorCode", t.getErrorCode());
                    }
                    listener.step(u);
                }
                if (TaskStatus.valueOf(t.getStatus()).terminal()) break;
                sleep(400);
            }
            JsonNode result = null;
            if (t.getResultJson() != null) {
                try {
                    result = mapper.readTree(t.getResultJson());
                } catch (Exception ignored) {
                    // leave null
                }
            }
            return new StepResult(stepId, tool, t.getStatus(), t.getId(), t.getOutputFile(), params, result, t.getMessage());
        } finally {
            runningTasks.remove(t.getId());
        }
    }

    private StepResult skip(int id, String tool, JsonNode params, String why, Listener listener) {
        ObjectNode u = update(id, "SKIPPED");
        u.put("message", why);
        listener.step(u);
        return new StepResult(id, tool, "SKIPPED", null, null, params, null, why);
    }

    private ObjectNode update(int stepId, String status) {
        return mapper.createObjectNode().put("stepId", stepId).put("status", status);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
