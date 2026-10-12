package com.houqing.tools.video_compressor_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.ai.prompt.PromptRegistry;
import com.houqing.tools.video_compressor_backend.ai.store.AiEntities;
import com.houqing.tools.video_compressor_backend.ai.store.ConversationEvents;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/** AI mode API. Model calls happen server-side only; the browser never sees the DeepSeek key. */
@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiOrchestrator ai;
    private final ConversationEvents events;
    private final PromptRegistry prompts;
    private final ObjectMapper mapper;

    public AiController(AiOrchestrator ai, ConversationEvents events, PromptRegistry prompts, ObjectMapper mapper) {
        this.ai = ai;
        this.events = events;
        this.prompts = prompts;
        this.mapper = mapper;
    }

    public record CreateRequest(@Size(max = 80) String title) {}
    public record MessageRequest(@NotBlank @Size(max = 2000) String text, @Size(max = 10) List<String> fileIds) {}
    public record ConfirmRequest(int version, boolean approve) {}

    public record ConversationView(String id, String title, String state, int planVersion, JsonNode plan,
                                   JsonNode context, String promptVersion, long createdAt, long updatedAt) {}

    @PostMapping("/conversations")
    public ConversationView create(@Valid @RequestBody(required = false) CreateRequest req) {
        return view(ai.create(CurrentUser.id(), req == null ? null : req.title()));
    }

    @GetMapping("/conversations")
    public Page<ConversationView> list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ai.list(CurrentUser.id(), page, size).map(this::view);
    }

    /** Full snapshot for (re)loading a page: conversation, messages and the seq to resume events from. */
    @GetMapping("/conversations/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        AiEntities.Conversation c = ai.require(CurrentUser.id(), id);
        List<Map<String, Object>> msgs = new ArrayList<>();
        for (AiEntities.Message m : ai.messages(id)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", m.getId());
            x.put("role", m.getRole());
            x.put("agent", m.getAgent());
            x.put("content", m.getContent());
            x.put("createdAt", m.getCreatedAt());
            msgs.add(x);
        }
        List<Map<String, Object>> evts = new ArrayList<>();
        for (AiEntities.Event e : events.history(id, 0)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("seq", e.getSeq());
            x.put("type", e.getType());
            x.put("payload", read(e.getPayloadJson()));
            evts.add(x);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation", view(c));
        body.put("messages", msgs);
        body.put("events", evts);
        return body;
    }

    @PostMapping("/conversations/{id}/messages")
    public ConversationView send(@PathVariable String id, @Valid @RequestBody MessageRequest req) {
        return view(ai.send(CurrentUser.id(), id, req.text(), req.fileIds()));
    }

    @PostMapping("/conversations/{id}/confirm")
    public ConversationView confirm(@PathVariable String id, @RequestBody ConfirmRequest req) {
        return view(ai.confirm(CurrentUser.id(), id, req.version(), req.approve()));
    }

    @PostMapping("/conversations/{id}/cancel")
    public ConversationView cancel(@PathVariable String id) {
        return view(ai.cancel(CurrentUser.id(), id));
    }

    /** Resumable event stream: Last-Event-ID (or ?after=) replays everything the client missed. */
    @GetMapping(value = "/conversations/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id,
                             @RequestParam(value = "after", required = false) Long after,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        ai.require(CurrentUser.id(), id);
        long from = after != null ? after : parse(lastEventId);
        return events.subscribe(id, from);
    }

    /** Agent roster with prompt/skill versions, for the UI and for traceability. */
    @GetMapping("/agents")
    public Map<String, Object> agents() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PromptRegistry.Skill s : prompts.skills()) {
            list.add(Map.of("id", s.id(), "title", s.title(), "agent", s.agentNo(), "version", s.version(),
                    "tools", s.tools(), "llm", s.terminalTool() != null || s.id().equals("quality-reviewer")));
        }
        return Map.of("promptVersion", prompts.activeVersion(), "agents", list);
    }

    private ConversationView view(AiEntities.Conversation c) {
        return new ConversationView(c.getId(), c.getTitle(), c.getState(), c.getPlanVersion(),
                c.getPlanJson() == null ? null : read(c.getPlanJson()), read(c.getContextJson()),
                c.getPromptVersion(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private static long parse(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
