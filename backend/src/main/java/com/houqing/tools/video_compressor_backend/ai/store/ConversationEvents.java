package com.houqing.tools.video_compressor_backend.ai.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Durable, replayable event log for a conversation. Every event gets a monotonically increasing seq that is also
 * the SSE id; a reconnecting client sends Last-Event-ID (or ?after=) and receives exactly what it missed, so
 * events are never lost and duplicates can be dropped client-side by seq. Text deltas are fan-out only (not
 * persisted) — the final message text is persisted as a "message" event, which is what a replay shows.
 */
@Component
public class ConversationEvents {
    private static final Logger log = LoggerFactory.getLogger(ConversationEvents.class);

    private final EventRepository repo;
    private final ObjectMapper mapper;
    private final Map<String, List<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public ConversationEvents(EventRepository repo, ObjectMapper mapper) {
        this.repo = repo;
        this.mapper = mapper;
    }

    /** Persists and broadcasts; returns the seq. */
    public long emit(String conversationId, String type, Object payload) {
        AiEntities.Event e = new AiEntities.Event();
        e.setConversationId(conversationId);
        e.setType(type);
        e.setPayloadJson(toJson(payload));
        e.setCreatedAt(System.currentTimeMillis());
        e = repo.save(e);
        broadcast(conversationId, e.getSeq(), type, e.getPayloadJson());
        return e.getSeq();
    }

    /** Ephemeral (not persisted): streamed text fragments. */
    public void emitTransient(String conversationId, String type, Object payload) {
        broadcast(conversationId, null, type, toJson(payload));
    }

    public SseEmitter subscribe(String conversationId, long afterSeq) {
        SseEmitter emitter = new SseEmitter(0L);
        List<SseEmitter> list = subscribers.computeIfAbsent(conversationId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(t -> remove.run());
        // replay after subscribing: anything emitted in between is delivered twice at worst, and the client
        // drops it by seq — never lost
        for (AiEntities.Event e : repo.findTop500ByConversationIdAndSeqGreaterThanOrderBySeqAsc(conversationId, afterSeq)) {
            if (!send(emitter, e.getSeq(), e.getType(), e.getPayloadJson())) break;
        }
        return emitter;
    }

    public List<AiEntities.Event> history(String conversationId, long afterSeq) {
        return repo.findTop500ByConversationIdAndSeqGreaterThanOrderBySeqAsc(conversationId, afterSeq);
    }

    private void broadcast(String conversationId, Long seq, String type, String json) {
        List<SseEmitter> list = subscribers.get(conversationId);
        if (list == null) return;
        for (SseEmitter em : list) {
            if (!send(em, seq, type, json)) list.remove(em);
        }
    }

    private static boolean send(SseEmitter em, Long seq, String type, String json) {
        try {
            SseEmitter.SseEventBuilder b = SseEmitter.event().name(type).data(json);
            if (seq != null) b.id(String.valueOf(seq));
            em.send(b);
            return true;
        } catch (IOException | IllegalStateException ex) {
            return false;
        }
    }

    @Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        subscribers.forEach((id, list) -> {
            for (SseEmitter em : list) {
                try {
                    em.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ex) {
                    list.remove(em);
                }
            }
        });
    }

    private String toJson(Object payload) {
        try {
            return payload instanceof JsonNode n ? n.toString() : mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("event serialisation failed: {}", e.getMessage());
            return "{}";
        }
    }
}
