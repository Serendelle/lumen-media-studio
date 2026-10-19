package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The single gateway to the file system. Clients only ever name a file by its opaque id; this service
 * resolves it to a path, checks ownership, and guarantees every path stays inside the storage root.
 */
@Service
public class StorageService {
    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final AppProperties props;
    private final FileTypeDetector detector;
    private final MediaFileRepository repo;
    private final Path storageRoot;
    private final Path workRoot;

    public StorageService(AppProperties props, FileTypeDetector detector, MediaFileRepository repo) {
        this.props = props;
        this.detector = detector;
        this.repo = repo;
        this.storageRoot = props.dataRoot().resolve("storage");
        this.workRoot = props.dataRoot().resolve("work");
    }

    // ---- storing ----

    @Transactional
    public MediaFile storeUpload(long ownerId, MultipartFile file, String role) {
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请选择一个文件上传");
        String original = sanitizeName(file.getOriginalFilename());
        String id = UUID.randomUUID().toString();
        String storedName = id + ".upload";
        Path dest = storageRoot.resolve(storedName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "文件保存失败");
        }
        FileTypeDetector.Detected d;
        try {
            d = detector.detect(dest, original);
        } catch (RuntimeException e) {
            quietDelete(dest);
            throw e;
        }
        // rename to the real, detected extension so downstream tools and downloads are consistent
        String finalStored = id + "." + d.ext();
        Path finalPath = storageRoot.resolve(finalStored);
        try {
            Files.move(dest, finalPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            quietDelete(dest);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "文件保存失败");
        }

        MediaFile mf = new MediaFile();
        mf.setId(id);
        mf.setOwnerId(ownerId);
        mf.setStoredName(finalStored);
        mf.setOriginalName(original);
        mf.setRole(role);
        mf.setMediaType(d.kind().name());
        mf.setMimeType(d.mime());
        mf.setSizeBytes(sizeOf(finalPath));
        long now = System.currentTimeMillis();
        mf.setCreatedAt(now);
        mf.setExpiresAt(now + Duration.ofHours(Math.max(1, props.retentionHours())).toMillis());
        return repo.save(mf);
    }

    /** Registers a file the backend produced itself (output of a task) under the owner. */
    @Transactional
    public MediaFile registerOutput(long ownerId, Path producedFile, String originalName, MediaKind kind,
                                     String mime, String taskId) {
        String id = UUID.randomUUID().toString();
        String ext = extensionOf(producedFile.getFileName().toString());
        String storedName = id + (ext.isEmpty() ? "" : "." + ext);
        Path dest = storageRoot.resolve(storedName);
        try {
            Files.createDirectories(storageRoot);
            Files.move(producedFile, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MediaFile mf = new MediaFile();
        mf.setId(id);
        mf.setOwnerId(ownerId);
        mf.setStoredName(storedName);
        mf.setOriginalName(originalName);
        mf.setRole("OUTPUT");
        mf.setMediaType(kind.name());
        mf.setMimeType(mime);
        mf.setSizeBytes(sizeOf(dest));
        mf.setSourceTaskId(taskId);
        long now = System.currentTimeMillis();
        mf.setCreatedAt(now);
        mf.setExpiresAt(now + Duration.ofHours(Math.max(1, props.retentionHours())).toMillis());
        return repo.save(mf);
    }

    // ---- resolving (ownership enforced) ----

    public MediaFile require(long ownerId, String fileId) {
        return repo.findByIdAndOwnerId(fileId, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "文件不存在或无权访问"));
    }

    public java.util.Optional<MediaFile> find(long ownerId, String fileId) {
        return repo.findByIdAndOwnerId(fileId, ownerId);
    }

    /** Absolute path of a stored file, re-checked to be inside the storage root. */
    public Path pathOf(MediaFile mf) {
        return insideRoot(storageRoot, mf.getStoredName());
    }

    public Path pathOf(long ownerId, String fileId) {
        return pathOf(require(ownerId, fileId));
    }

    // ---- per-task work dirs ----

    public Path newWorkDir(String taskId) {
        Path dir = workRoot.resolve(taskId);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }

    public void deleteWorkDir(String taskId) {
        deleteRecursively(workRoot.resolve(taskId));
    }

    // ---- maintenance ----

    @Transactional
    public int purgeExpired() {
        long now = System.currentTimeMillis();
        List<MediaFile> expired = repo.findByExpiresAtLessThan(now);
        for (MediaFile mf : expired) {
            quietDelete(storageRoot.resolve(mf.getStoredName()));
            deleteWorkDir("doc-preview-" + mf.getId());
            repo.delete(mf);
        }
        if (!expired.isEmpty()) log.info("purged {} expired files", expired.size());
        return expired.size();
    }

    public void deleteFile(MediaFile mf) {
        quietDelete(storageRoot.resolve(mf.getStoredName()));
        deleteWorkDir("doc-preview-" + mf.getId()); // rendered page previews of a PDF
        repo.delete(mf);
    }

    public long usableSpaceMb() {
        return storageRoot.toFile().getUsableSpace() / (1024 * 1024);
    }

    public void ensureDiskSpace() {
        if (usableSpaceMb() < props.media().minFreeDiskMb()) {
            throw new ApiException(ErrorCode.INSUFFICIENT_DISK);
        }
    }

    // ---- helpers ----

    /** Resolve `name` under `root` and fail if the result escapes it (defuses any ".." or absolute path). */
    private Path insideRoot(Path root, String name) {
        Path base = root.toAbsolutePath().normalize();
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            log.warn("path traversal blocked: {}", name);
            throw new ApiException(ErrorCode.FORBIDDEN, "非法的文件路径");
        }
        return resolved;
    }

    public static String sanitizeName(String name) {
        if (name == null || name.isBlank()) return "file";
        String base = name.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("[\\x00-\\x1f<>:\"/\\\\|?*]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) base = "file";
        return base.length() > 200 ? base.substring(base.length() - 200) : base;
    }

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "";
    }

    public static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    private void quietDelete(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("delete failed: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            log.debug("work dir cleanup failed: {}", e.getMessage());
        }
    }

    public Path storageRoot() { return storageRoot; }
}
