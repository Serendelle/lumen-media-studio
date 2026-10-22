package com.houqing.tools.video_compressor_backend.media;

/** Shared between a running task and the cancel endpoint; cancelling kills the attached process. */
public class CancellationToken {
    private volatile boolean cancelled;
    private volatile Process current;

    public boolean isCancelled() { return cancelled; }

    public void throwIfCancelled() {
        if (cancelled) throw new com.houqing.tools.video_compressor_backend.common.ApiException(
                com.houqing.tools.video_compressor_backend.common.ErrorCode.CANCELLED);
    }

    public void cancel() {
        cancelled = true;
        Process p = current;
        if (p != null) ProcessRunner.kill(p);
    }

    void attach(Process p) {
        current = p;
        if (cancelled) ProcessRunner.kill(p);
    }

    void detach() { current = null; }
}
