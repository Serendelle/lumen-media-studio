package com.houqing.tools.video_compressor_backend.media;

public enum MediaKind {
    VIDEO, AUDIO, IMAGE, PDF, ARCHIVE;

    public boolean isAv() { return this == VIDEO || this == AUDIO; }
}
