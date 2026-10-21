package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping({"/api/v2/specs/{projectId}/form", "/api/public/collections/{token}/form"})
public class SpecFormController {
    private final SpecFormService forms;
    private final StorageService storage;
    private final CollectionService collections;
    private final Map<String, long[]> rate = new LinkedHashMap<>();

    public SpecFormController(SpecFormService forms, StorageService storage, CollectionService collections) {
        this.forms = forms; this.storage = storage; this.collections = collections;
    }

    private SpecFormService.Access access(Map<String, String> vars, HttpServletRequest req) {
        if (vars.containsKey("token")) return SpecFormService.Access.shared(vars.get("token"), CurrentUser.optionalId().orElse(null), req.getHeader("X-Participant-Token"));
        return SpecFormService.Access.owner(vars.get("projectId"), CurrentUser.id());
    }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    public record Settings(JsonNode config, int version) {}
    public record Join(String name, String secret) {}
    public record Member(String username, String role) {}
    public record Create(JsonNode values, String requestKey, int configVersion) {}
    public record Update(JsonNode values, int version, int configVersion) {}
    public record Comment(String content) {}

    @GetMapping
    public ResponseEntity<ObjectNode> settings(@PathVariable Map<String, String> vars, HttpServletRequest req) {
        return response(forms.settings(access(vars, req)));
    }
    @PutMapping
    public ResponseEntity<ObjectNode> save(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Settings body) {
        return response(forms.saveSettings(access(vars, req), body.config(), body.version()));
    }
    @PostMapping("/participants")
    public ResponseEntity<ObjectNode> join(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Join body) {
        limit(req); return response(forms.join(access(vars, req), body.name(), body.secret()));
    }
    @GetMapping("/members")
    public ResponseEntity<?> members(@PathVariable Map<String, String> vars, HttpServletRequest req) { return response(forms.members(access(vars, req))); }
    @PutMapping("/members")
    public ResponseEntity<Void> member(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Member body) {
        forms.member(access(vars, req), body.username(), body.role()); return response(null);
    }
    @GetMapping("/rows")
    public ResponseEntity<ObjectNode> board(@PathVariable Map<String, String> vars, HttpServletRequest req,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size, @RequestParam(defaultValue = "false") boolean deleted) {
        return response(forms.board(access(vars, req), page, size, deleted));
    }
    @PostMapping("/rows")
    public ResponseEntity<ObjectNode> create(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Create body) {
        limit(req); return response(forms.create(access(vars, req), body.values(), body.requestKey(), body.configVersion()));
    }
    @PatchMapping("/rows/{rowId}")
    public ResponseEntity<ObjectNode> update(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Update body) {
        limit(req); return response(forms.update(access(vars, req), vars.get("rowId"), body.values(), body.version(), body.configVersion()));
    }
    @DeleteMapping("/rows/{rowId}")
    public ResponseEntity<Void> delete(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestParam int version) {
        limit(req); forms.deleteOrRestore(access(vars, req), vars.get("rowId"), version, false); return response(null);
    }
    @PostMapping("/rows/{rowId}/restore")
    public ResponseEntity<Void> restore(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestParam int version) {
        forms.deleteOrRestore(access(vars, req), vars.get("rowId"), version, true); return response(null);
    }
    @GetMapping("/rows/{rowId}/discussion")
    public ResponseEntity<ObjectNode> discussion(@PathVariable Map<String, String> vars, HttpServletRequest req) {
        return response(forms.discussion(access(vars, req), vars.get("rowId")));
    }
    @PostMapping("/rows/{rowId}/comments")
    public ResponseEntity<Void> comment(@PathVariable Map<String, String> vars, HttpServletRequest req, @RequestBody Comment body) {
        limit(req); forms.comment(access(vars, req), vars.get("rowId"), body.content()); return response(null);
    }
    @GetMapping("/statistics")
    public ResponseEntity<ObjectNode> statistics(@PathVariable Map<String, String> vars, HttpServletRequest req) { return response(forms.statistics(access(vars, req))); }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable Map<String, String> vars, HttpServletRequest req) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("收集表汇总.xlsx", StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(forms.export(access(vars, req)));
    }
    @GetMapping("/rows/{rowId}/files/{itemId}")
    public ResponseEntity<FileSystemResource> file(@PathVariable Map<String, String> vars, HttpServletRequest req) {
        var file = forms.attachment(access(vars, req), vars.get("rowId"), vars.get("itemId"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(storage.pathOf(file)));
    }
    @PostMapping(value = "/rows/{rowId}/files", consumes = "multipart/form-data")
    public ResponseEntity<?> upload(@PathVariable Map<String, String> vars, HttpServletRequest req,
            @RequestParam org.springframework.web.multipart.MultipartFile file, @RequestParam String slotKey, @RequestParam String requestKey) {
        limit(req);
        var access = access(vars, req);
        if (vars.containsKey("token")) return response(collections.submit(vars.get("token"), "附件提交", "", slotKey, requestKey, file, vars.get("rowId"), access));
        forms.uploadOwner(access, vars.get("rowId"), slotKey, file); return response(null);
    }
    private synchronized void limit(HttpServletRequest request) {
        if (CurrentUser.optionalId().isPresent()) return;
        long now = System.currentTimeMillis(); rate.entrySet().removeIf(e -> now - e.getValue()[0] > 60000);
        if (rate.size() >= 10000 && !rate.containsKey(request.getRemoteAddr())) throw new ApiException(ErrorCode.RATE_LIMITED);
        long[] bucket = rate.computeIfAbsent(request.getRemoteAddr(), ip -> new long[]{now, 0});
        if (++bucket[1] > 60) throw new ApiException(ErrorCode.RATE_LIMITED, "操作过于频繁，请稍后重试");
    }
}
