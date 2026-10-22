package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.media.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Unified task API: the same engine serves Professional mode and the AI executor. */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskEngine engine;
    private final TaskEventHub hub;
    private final TaskHistoryService history;

    public TaskController(TaskEngine engine, TaskEventHub hub, TaskHistoryService history) {
        this.engine = engine;
        this.hub = hub;
        this.history = history;
    }

    public record CreateTaskRequest(@NotBlank String type,
                                    @NotEmpty @Size(max = 200) List<@NotBlank String> inputs,
                                    JsonNode params) {}

    @PostMapping
    public TaskView create(@Valid @RequestBody CreateTaskRequest req,
                           @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long uid = CurrentUser.id();
        JsonNode params = req.params() == null ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode() : req.params();
        return engine.toView(engine.submit(uid, req.type(), params, req.inputs(), "PRO", key));
    }

    @GetMapping
    /** status = optional comma-separated filter, e.g. "QUEUED,RUNNING". */
    public Page<TaskView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
                               @RequestParam(required = false) String status) {
        List<String> statuses = null;
        if (status != null && !status.isBlank()) {
            statuses = java.util.Arrays.stream(status.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            for (String s : statuses) {
                try {
                    TaskStatus.valueOf(s);
                } catch (IllegalArgumentException e) {
                    throw new com.houqing.tools.video_compressor_backend.common.ApiException(
                            com.houqing.tools.video_compressor_backend.common.ErrorCode.VALIDATION_FAILED, "未知的任务状态: " + s);
                }
            }
        }
        return engine.list(CurrentUser.id(), statuses, page, size).map(engine::toView);
    }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) {
        return engine.toView(history.requireVisible(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/cancel")
    public TaskView cancel(@PathVariable String id) {
        history.requireVisible(CurrentUser.id(), id);
        return engine.toView(engine.cancel(CurrentUser.id(), id));
    }

    @PostMapping("/{id}/retry")
    public TaskView retry(@PathVariable String id) {
        history.requireVisible(CurrentUser.id(), id);
        return engine.toView(engine.retry(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public TaskHistoryService.DeleteResult delete(@PathVariable String id) {
        return history.delete(CurrentUser.id(), List.of(id));
    }

    public record DeleteHistoryRequest(@NotEmpty @Size(max = 200) List<@NotBlank String> taskIds) {}

    @PostMapping("/history/delete")
    public TaskHistoryService.DeleteResult deleteMany(@Valid @RequestBody DeleteHistoryRequest req) {
        return history.delete(CurrentUser.id(), req.taskIds());
    }

    @DeleteMapping("/history")
    public TaskHistoryService.ClearResult clearHistory() { return history.clear(CurrentUser.id()); }

    /**
     * Real-time progress over SSE. Subscribes first, then sends the persisted snapshot, so no transition between
     * the two can be lost; a reconnecting client (or a page refresh) gets the current state immediately.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) throws IOException {
        long uid = CurrentUser.id();
        TaskEntity owned = history.requireVisible(uid, id); // visibility and ownership before subscribing
        SseEmitter emitter = hub.subscribe(owned.getId());
        TaskEntity t;
        try { t = history.requireVisible(uid, id); }
        catch (RuntimeException e) { emitter.complete(); throw e; }
        emitter.send(SseEmitter.event().name("status").data(engine.toView(t)));
        if (TaskStatus.valueOf(t.getStatus()).terminal()) hub.complete(id);
        return emitter;
    }

    @GetMapping("/types")
    public Map<String, Object> types() {
        return Map.of("types", List.of("video.compress", "video.convert", "video.watermark", "audio.extract",
                "image.process", "image.removebg", "pdf.process", "metadata.scrub", "metadata.modify"));
    }
}
