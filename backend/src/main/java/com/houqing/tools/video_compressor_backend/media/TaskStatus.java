package com.houqing.tools.video_compressor_backend.media;

/** Unified task lifecycle shared by Professional and AI modes. */
public enum TaskStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
