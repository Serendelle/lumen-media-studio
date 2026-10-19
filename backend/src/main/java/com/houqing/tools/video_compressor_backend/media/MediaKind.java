package com.houqing.tools.video_compressor_backend.media;

public enum MediaKind {
    VIDEO, AUDIO, IMAGE, PDF, ARCHIVE,
    /** .docx / .xlsx / single static .html (V2 Document Studio). PDF keeps its own kind. */
    DOCUMENT;

    public boolean isAv() { return this == VIDEO || this == AUDIO; }
}
