package com.houqing.tools.video_compressor_backend.auth;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Simple fixed-window limiter: at most N failed logins per key (ip+username) per window. */
@Component
public class LoginRateLimiter {
    static final int MAX_FAILURES = 8;
    static final long WINDOW_MS = 10 * 60 * 1000L;

    private record Window(long start, int failures) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        Window w = windows.get(key);
        if (w == null) return false;
        if (System.currentTimeMillis() - w.start() > WINDOW_MS) {
            windows.remove(key);
            return false;
        }
        return w.failures() >= MAX_FAILURES;
    }

    public void recordFailure(String key) {
        long now = System.currentTimeMillis();
        windows.compute(key, (k, w) -> (w == null || now - w.start() > WINDOW_MS)
                ? new Window(now, 1) : new Window(w.start(), w.failures() + 1));
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start() > WINDOW_MS);
        }
    }

    public void reset(String key) { windows.remove(key); }
}
