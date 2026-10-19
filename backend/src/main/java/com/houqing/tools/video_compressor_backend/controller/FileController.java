package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.auth.DownloadTicketService;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Set;

/** Generic file API used by both modes (AI mode uploads any supported media through here). */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private final UploadService uploads;
    private final StorageService storage;
    private final MediaFileRepository repo;
    private final DownloadTicketService tickets;
    private final FileStreamer streamer;

    public FileController(UploadService uploads, StorageService storage, MediaFileRepository repo,
                          DownloadTicketService tickets, FileStreamer streamer) {
        this.uploads = uploads;
        this.storage = storage;
        this.repo = repo;
        this.tickets = tickets;
        this.streamer = streamer;
    }

    @PostMapping("/upload")
    public FileView upload(@RequestParam("file") MultipartFile file) {
        MediaFile mf = uploads.upload(CurrentUser.id(), file,
                Set.of(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE, MediaKind.PDF, MediaKind.DOCUMENT));
        return uploads.view(mf);
    }

    /** role = INPUT | OUTPUT */
    @GetMapping
    public Page<FileView> list(@RequestParam(defaultValue = "INPUT") String role,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {
        if (!role.equals("INPUT") && !role.equals("OUTPUT")) throw new ApiException(ErrorCode.VALIDATION_FAILED, "role 仅支持 INPUT/OUTPUT");
        return repo.findByOwnerIdAndRole(CurrentUser.id(), role,
                        PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(uploads::view);
    }

    @GetMapping("/{id}")
    public FileView get(@PathVariable String id) {
        return uploads.view(storage.require(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        storage.deleteFile(storage.require(CurrentUser.id(), id));
        return ResponseEntity.noContent().build();
    }

    /** Short-lived URL for &lt;video&gt;/&lt;img&gt; previews and plain download links (no header needed). */
    @PostMapping("/{id}/ticket")
    public Map<String, Object> ticket(@PathVariable String id) {
        long uid = CurrentUser.id();
        storage.require(uid, id);
        DownloadTicketService.Ticket t = tickets.issue(uid, id);
        return Map.of("ticket", t.value(), "expiresAt", t.expiresAt(),
                "url", "/api/files/" + id + "/content?ticket=" + t.value(),
                "downloadUrl", "/api/files/" + id + "/content?download=1&ticket=" + t.value());
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable String id,
                                            @RequestParam(value = "ticket", required = false) String ticket,
                                            @RequestParam(value = "download", defaultValue = "0") int download) {
        return streamer.stream(id, ticket, download == 1);
    }
}
