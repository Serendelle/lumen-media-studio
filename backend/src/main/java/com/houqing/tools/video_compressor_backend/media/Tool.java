package com.houqing.tools.video_compressor_backend.media;

public enum Tool {
    FFMPEG("FFmpeg"), FFPROBE("FFprobe"), EXIFTOOL("ExifTool"), MAGICK("ImageMagick"),
    GHOSTSCRIPT("Ghostscript"), REMBG("rembg"),
    SOFFICE("LibreOffice"), CHROMIUM("Chromium 内核浏览器（Chrome / Edge）");

    private final String label;
    Tool(String label) { this.label = label; }
    public String label() { return label; }
}
