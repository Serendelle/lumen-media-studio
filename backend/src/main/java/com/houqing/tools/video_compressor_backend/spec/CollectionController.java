package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@RestController
public class CollectionController {
    private final CollectionService collections;
    private final SpecOrganizer organizer;
    private final Map<String, long[]> limits = new LinkedHashMap<>();
    public CollectionController(CollectionService collections, SpecOrganizer organizer) {
        this.collections = collections; this.organizer = organizer;
    }

    @GetMapping("/api/v2/specs/{id}/collections")
    public List<ObjectNode> list(@PathVariable String id) { return collections.list(CurrentUser.id(), id); }
    public record Create(int days) {}
    @PostMapping("/api/v2/specs/{id}/collections")
    public ObjectNode create(@PathVariable String id, @RequestBody Create body) { return collections.create(CurrentUser.id(), id, body.days()); }
    @DeleteMapping("/api/v2/specs/{id}/collections/{linkId}")
    public ResponseEntity<Void> revoke(@PathVariable String id, @PathVariable String linkId) {
        collections.revoke(CurrentUser.id(), id, linkId); return ResponseEntity.noContent().build();
    }
    @PostMapping("/api/v2/specs/{id}/organize")
    public ObjectNode organize(@PathVariable String id) { return organizer.organize(CurrentUser.id(), id); }

    @GetMapping("/api/public/collections/{token}")
    public ResponseEntity<ObjectNode> info(@PathVariable String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(collections.publicView(token));
    }
    @PostMapping(value = "/api/public/collections/{token}/submissions", consumes = "multipart/form-data")
    public ResponseEntity<ObjectNode> submit(@PathVariable String token, @RequestParam String name,
            @RequestParam(defaultValue = "") String identifier, @RequestParam String slotKey,
            @RequestParam String requestKey, @RequestParam MultipartFile file, HttpServletRequest request) {
        limit(request.getRemoteAddr()); // ignore caller-controlled forwarding headers
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(collections.submit(token, name, identifier, slotKey, requestKey, file));
    }

    private synchronized void limit(String ip) {
        long now = System.currentTimeMillis();
        limits.entrySet().removeIf(e -> now - e.getValue()[0] >= 60_000);
        if (!limits.containsKey(ip) && limits.size() >= 10_000) throw new ApiException(ErrorCode.RATE_LIMITED);
        long[] bucket = limits.computeIfAbsent(ip, k -> new long[]{now, 0});
        if (++bucket[1] > 60) throw new ApiException(ErrorCode.RATE_LIMITED, "提交过于频繁，请稍后重试");
    }
}
