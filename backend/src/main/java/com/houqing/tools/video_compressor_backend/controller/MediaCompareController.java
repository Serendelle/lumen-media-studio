package com.houqing.tools.video_compressor_backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Side-by-side facts for a source and a result (both must belong to the caller). Every number is measured now:
 * bytes from the file system, streams from a fresh FFprobe — nothing is taken from the task summary.
 */
@RestController
@RequestMapping("/api/v2/media")
public class MediaCompareController {
    private final StorageService storage;
    private final MediaVerifier verifier;
    private final ObjectMapper mapper;

    public MediaCompareController(StorageService storage, MediaVerifier verifier, ObjectMapper mapper) {
        this.storage = storage;
        this.verifier = verifier;
        this.mapper = mapper;
    }

    @GetMapping("/compare")
    public ObjectNode compare(@RequestParam String sourceFileId, @RequestParam String outputFileId) {
        long uid = CurrentUser.id();
        MediaFile src = storage.require(uid, sourceFileId);
        MediaFile out = storage.require(uid, outputFileId);
        if (!src.kind().isAv() || !(out.kind().isAv() || "gif".equals(StorageService.extensionOf(out.getStoredName())))) {
            throw new ApiException(ErrorCode.CAPABILITY_UNSUPPORTED, "仅支持音视频文件的对比");
        }
        ObjectNode r = mapper.createObjectNode();
        ObjectNode s = side(src);
        ObjectNode o = side(out);
        r.set("source", s);
        r.set("output", o);
        long a = s.path("sizeBytes").asLong(), b = o.path("sizeBytes").asLong();
        r.put("deltaBytes", b - a);
        if (a > 0) r.put("savedPercent", Math.round((1 - (double) b / a) * 1000) / 10.0);
        double da = s.path("measured").path("duration").asDouble(0), db = o.path("measured").path("duration").asDouble(0);
        if (da > 0 && db > 0) r.put("durationDeltaMs", Math.round((db - da) * 1000));
        return r;
    }

    private ObjectNode side(MediaFile f) {
        ObjectNode n = mapper.createObjectNode();
        n.put("fileId", f.getId());
        n.put("filename", f.getOriginalName());
        n.put("mediaType", f.getMediaType());
        MediaVerifier.Verification v = verifier.start();
        verifier.measure(v, storage.pathOf(f));
        n.put("sizeBytes", v.measured().path("actualBytes").asLong());
        n.set("measured", v.measured());
        n.put("readable", v.passed());
        return n;
    }
}
