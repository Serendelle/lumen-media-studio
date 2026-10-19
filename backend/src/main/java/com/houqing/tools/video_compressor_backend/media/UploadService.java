package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/**
 * Upload pipeline shared by every entry point: store → detect real type → enforce the endpoint's allowed kinds
 * and per-kind size limit → probe audio/video (a file FFmpeg cannot read is rejected and deleted).
 */
@Service
public class UploadService {
    static final Map<MediaKind, Long> MAX_BYTES = Map.of(
            MediaKind.IMAGE, 50L * 1024 * 1024,
            MediaKind.PDF, 200L * 1024 * 1024,
            MediaKind.AUDIO, 1024L * 1024 * 1024,
            MediaKind.VIDEO, Long.MAX_VALUE); // bounded by spring.servlet.multipart.max-file-size

    private final StorageService storage;
    private final MediaProbe probe;
    private final MediaFileRepository repo;
    private final ObjectMapper mapper;

    public UploadService(StorageService storage, MediaProbe probe, MediaFileRepository repo, ObjectMapper mapper) {
        this.storage = storage;
        this.probe = probe;
        this.repo = repo;
        this.mapper = mapper;
    }

    public MediaFile upload(long ownerId, MultipartFile file, Set<MediaKind> allowed) {
        storage.ensureDiskSpace();
        MediaFile mf = storage.storeUpload(ownerId, file, "INPUT");
        try {
            if (!allowed.contains(mf.kind())) {
                throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "该入口不接受此类文件（检测为 " + mf.getMediaType() + "）");
            }
            if (mf.getSizeBytes() > MAX_BYTES.getOrDefault(mf.kind(), Long.MAX_VALUE)) {
                throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                        mf.getMediaType() + " 文件不能超过 " + MAX_BYTES.get(mf.kind()) / 1024 / 1024 + "MB");
            }
            ObjectNode info = null;
            if (mf.kind().isAv()) {
                MediaProbe.MediaInfo mi = probe.probe(storage.pathOf(mf)); // throws MEDIA_UNREADABLE
                if (mf.kind() == MediaKind.VIDEO && !mi.hasVideo() && mi.hasAudio()) {
                    // an .m4a shares the MP4 container signature: classify by the streams actually present
                    mf.setMediaType(MediaKind.AUDIO.name());
                    mf.setMimeType("audio/mp4");
                    if (!allowed.contains(MediaKind.AUDIO)) {
                        throw new ApiException(ErrorCode.UNSUPPORTED_MEDIA, "该入口不接受纯音频文件");
                    }
                }
                if (mf.kind() == MediaKind.VIDEO && !mi.hasVideo()) {
                    throw new ApiException(ErrorCode.MEDIA_UNREADABLE, "未检测到视频画面");
                }
                info = summarize(mi);
            } else if (mf.kind() == MediaKind.IMAGE) {
                info = imageInfo(mf);
            } else if (mf.kind() == MediaKind.DOCUMENT) {
                info = mapper.createObjectNode();
                info.put("format", StorageService.extensionOf(mf.getStoredName()));
                info.put("sha256", sha256(storage.pathOf(mf)));
            }
            if (info != null) {
                mf.setProbeJson(info.toString());
                mf = repo.save(mf);
            }
            return mf;
        } catch (RuntimeException e) {
            storage.deleteFile(mf);
            throw e;
        }
    }

    public FileView view(MediaFile mf) {
        JsonNode info = null;
        if (mf.getProbeJson() != null) {
            try {
                info = mapper.readTree(mf.getProbeJson());
            } catch (Exception ignored) {
                // corrupt cache: omit
            }
        }
        return new FileView(mf.getId(), mf.getId(), mf.getOriginalName(), mf.getRole(), mf.getMediaType(),
                mf.getMimeType(), mf.getSizeBytes(), mf.getSourceTaskId(), mf.getCreatedAt(), mf.getExpiresAt(), info);
    }

    ObjectNode summarize(MediaProbe.MediaInfo mi) {
        ObjectNode n = mapper.createObjectNode();
        n.put("formatName", mi.formatName());
        n.put("duration", mi.duration());
        n.put("bitrate", mi.overallBitrate());
        n.put("hasVideo", mi.hasVideo());
        n.put("hasAudio", mi.hasAudio());
        if (mi.hasVideo()) {
            n.put("width", mi.width());
            n.put("height", mi.height());
            n.put("fps", Math.round(mi.fps() * 100) / 100.0);
            n.put("videoCodec", mi.videoCodec());
            n.put("videoBitrate", mi.videoBitrate());
        }
        if (mi.hasAudio()) {
            n.put("audioCodec", mi.audioCodec());
            n.put("audioBitrate", mi.audioBitrate());
            n.put("audioChannels", mi.audioChannels());
            n.put("audioSampleRate", mi.audioSampleRate());
        }
        return n;
    }

    static String sha256(java.nio.file.Path p) {
        try (var in = java.nio.file.Files.newInputStream(p)) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** Best effort: FFprobe reads the common raster formats; formats it cannot read simply get no dimensions. */
    private ObjectNode imageInfo(MediaFile mf) {
        if ("svg".equals(StorageService.extensionOf(mf.getStoredName()))) return null;
        try {
            MediaProbe.MediaInfo mi = probe.probe(storage.pathOf(mf));
            ObjectNode n = mapper.createObjectNode();
            n.put("width", mi.width());
            n.put("height", mi.height());
            return n;
        } catch (ApiException e) {
            return null;
        }
    }
}
