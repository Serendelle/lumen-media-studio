package com.houqing.tools.video_compressor_backend.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fans out real task-state changes to subscribed SSE clients. Emitters are keyed by task id; a client
 * that reconnects simply resubscribes and the controller first replays the current persisted state.
 */
@Component
public class TaskEventHub {
    private static final Logger log = LoggerFactory.getLogger(TaskEventHub.class);

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; terminal event completes it
        emitters.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> remove(taskId, emitter));
        emitter.onError(e -> remove(taskId, emitter));
        return emitter;
    }

    public void publish(String taskId, String event, Object payload) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list == null) return;
        for (SseEmitter e : list) {
            try {
                e.send(SseEmitter.event().name(event).data(payload));
            } catch (IOException | IllegalStateException ex) {
                remove(taskId, e);
            }
        }
    }

    public void complete(String taskId) {
        List<SseEmitter> list = emitters.remove(taskId);
        if (list == null) return;
        for (SseEmitter e : list) {
            try {
                e.complete();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }
    }

    /** Comment-only heartbeat keeps idle SSE connections from being closed by proxies/browsers. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 15000)
    public void heartbeat() {
        emitters.forEach((taskId, list) -> {
            for (SseEmitter e : list) {
                try {
                    e.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ex) {
                    remove(taskId, e);
                }
            }
        });
    }

    private void remove(String taskId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(taskId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) emitters.remove(taskId);
        }
    }
}
