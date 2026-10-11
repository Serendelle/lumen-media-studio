package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.auth.DownloadTicketService;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.MediaFile;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Streams a stored file to its owner. Authorization is a bearer token or a signed ticket bound to this file;
 * Range requests (video seeking) are handled by Spring for Resource bodies. Responses are sandboxed so a file
 * can never execute script on this origin, and SVG is always downloaded rather than rendered.
 */
@Component
public class FileStreamer {
    private static final Set<String> INLINE_SAFE = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "audio/mpeg", "audio/mp4", "audio/aac", "audio/wav",
            "audio/flac", "audio/ogg", "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif");

    private final StorageService storage;
    private final DownloadTicketService tickets;

    public FileStreamer(StorageService storage, DownloadTicketService tickets) {
        this.storage = storage;
        this.tickets = tickets;
    }

    public ResponseEntity<Resource> stream(String fileId, String ticket, boolean forceDownload) {
        long uid = CurrentUser.optionalId()
                .or(() -> tickets.verify(ticket, fileId))
                .orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
        MediaFile mf = storage.require(uid, fileId);
        Path path = storage.pathOf(mf);
        if (!Files.isRegularFile(path)) throw new ApiException(ErrorCode.NOT_FOUND, "文件已过期或被清理");

        boolean inline = !forceDownload && INLINE_SAFE.contains(mf.getMimeType());
        ContentDisposition cd = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(mf.getOriginalName(), StandardCharsets.UTF_8).build();
        MediaType type = inline ? MediaType.parseMediaType(mf.getMimeType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=600")
                .body(new FileSystemResource(path));
    }
}
