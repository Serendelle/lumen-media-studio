package com.houqing.tools.video_compressor_backend.media.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.media.MediaFile;

/** Exposes the image handler's output-format rule so the AI plan validator predicts outputs identically. */
public final class ImageProcessHandlerAccess {
    private ImageProcessHandlerAccess() {}

    public static String outputExt(JsonNode params, MediaFile in) {
        return ImageProcessHandler.outputExt(params.path("tool").asText("compress"), params, in);
    }
}
