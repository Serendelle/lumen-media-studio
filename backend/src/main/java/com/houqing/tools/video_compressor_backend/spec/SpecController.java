package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.spec.SpecEntities.SpecItem;
import com.houqing.tools.video_compressor_backend.spec.SpecEntities.SpecProject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 材料规范官 API (V2). Every route checks that the project belongs to the caller; other users get 404. */
@RestController
@RequestMapping("/api/v2/specs")
public class SpecController {
    private final SpecService specs;
    private final SpecExtractor extractor;
    private final SpecItemRepository items;
    private final TaskEngine engine;
    private final TaskRepository tasks;
    private final StorageService storage;
    private final ObjectMapper mapper;
    private final SpecFormService forms;

    public SpecController(SpecService specs, SpecExtractor extractor, SpecItemRepository items, TaskEngine engine,
                          TaskRepository tasks, StorageService storage, ObjectMapper mapper, SpecFormService forms) {
        this.specs = specs;
        this.extractor = extractor;
        this.items = items;
        this.engine = engine;
        this.tasks = tasks;
        this.storage = storage;
        this.mapper = mapper;
        this.forms = forms;
    }

    public record CreateRequest(String name) {}

    @PostMapping
    public ResponseEntity<ObjectNode> create(@RequestBody CreateRequest req) {
        SpecProject p = specs.create(CurrentUser.id(), req.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(p));
    }

    @GetMapping
    public ObjectNode list(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        long uid = CurrentUser.id();
        var pg = specs.list(uid, page, size);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode arr = out.putArray("items");
        for (SpecProject p : pg.getContent()) {
            ObjectNode n = arr.addObject();
            n.put("id", p.getId());
            n.put("name", p.getName());
            n.put("slots", specs.spec(p).path("slots").size());
            JsonNode r = specs.roster(p);
            n.put("people", r == null ? 0 : r.path("people").size());
            n.put("files", items.countByProjectId(p.getId()));
            n.put("records", forms.recordCount(p.getId()));
            n.put("updatedAt", p.getUpdatedAt());
        }
        out.put("page", pg.getNumber());
        out.put("total", pg.getTotalElements());
        return out;
    }

    @GetMapping("/{id}")
    public ObjectNode get(@PathVariable String id) {
        return view(specs.require(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        specs.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    // ---------------- spec ----------------

    public record ExtractRequest(String noticeText) {}

    /** AI draft only — nothing is saved until the user confirms it with PUT /spec. */
    @PostMapping("/{id}/extract")
    public ObjectNode extract(@PathVariable String id, @RequestBody ExtractRequest req) {
        specs.require(CurrentUser.id(), id);
        SpecExtractor.Draft d = extractor.extract(req.noticeText());
        ObjectNode out = mapper.createObjectNode();
        out.set("spec", d.spec());
        out.set("errors", mapper.valueToTree(d.errors()));
        out.set("warnings", mapper.valueToTree(d.warnings()));
        out.put("model", d.model());
        out.put("promptVersion", d.promptVersion());
        out.put("rounds", d.rounds());
        return out;
    }

    public record SpecRequest(JsonNode spec, int version, String noticeText) {}

    @PutMapping("/{id}/spec")
    public ObjectNode saveSpec(@PathVariable String id, @RequestBody SpecRequest req) {
        SpecService.SchemaReport r = specs.saveSpec(CurrentUser.id(), id, req.spec(), req.version(), req.noticeText());
        ObjectNode out = view(specs.require(CurrentUser.id(), id));
        out.set("warnings", mapper.valueToTree(r.warnings()));
        return out;
    }

    // ---------------- roster & templates ----------------

    public record RosterRequest(String fileId, Integer headerRow, String nameField) {}

    @PostMapping("/{id}/roster")
    public ObjectNode roster(@PathVariable String id, @RequestBody RosterRequest req) {
        if (req.fileId() == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请选择名单文件");
        specs.importRoster(CurrentUser.id(), id, req.fileId(), req.headerRow(), req.nameField());
        return view(specs.require(CurrentUser.id(), id));
    }

    public record TemplateRequest(String docxFileId, String xlsxFileId, Integer xlsxHeaderRow) {}

    @PutMapping("/{id}/templates")
    public ObjectNode templates(@PathVariable String id, @RequestBody TemplateRequest req) {
        specs.setTemplates(CurrentUser.id(), id, req.docxFileId(), req.xlsxFileId(), req.xlsxHeaderRow());
        return view(specs.require(CurrentUser.id(), id));
    }

    // ---------------- items ----------------

    public record AttachRequest(@NotEmpty @Size(max = 500) List<SpecService.Attach> files) {}

    @PostMapping("/{id}/items")
    public ObjectNode attach(@PathVariable String id, @Valid @RequestBody AttachRequest req) {
        long uid = CurrentUser.id();
        specs.attach(uid, id, req.files());
        return board(id);
    }

    @GetMapping("/{id}/items")
    public ObjectNode board(@PathVariable String id) {
        long uid = CurrentUser.id();
        SpecProject p = specs.require(uid, id);
        List<SpecItem> list = specs.items(uid, id);
        ObjectNode out = specs.board(p, list);
        ArrayNode arr = out.putArray("items");
        for (SpecItem it : list) arr.add(specs.itemView(it, uid));
        return out;
    }

    public record AssignRequest(String personKey, String slotKey) {}

    @PatchMapping("/{id}/items/{itemId}")
    public ObjectNode assign(@PathVariable String id, @PathVariable String itemId, @RequestBody AssignRequest req) {
        long uid = CurrentUser.id();
        return specs.itemView(specs.assign(uid, id, itemId, req.personKey(), req.slotKey()), uid);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Void> removeItem(@PathVariable String id, @PathVariable String itemId) {
        specs.removeItem(CurrentUser.id(), id, itemId);
        return ResponseEntity.noContent().build();
    }

    public record FixRequest(List<String> itemIds) {}

    @PostMapping("/{id}/fix")
    public ObjectNode fix(@PathVariable String id, @RequestBody(required = false) FixRequest req) {
        long uid = CurrentUser.id();
        List<SpecItem> started = specs.fix(uid, id, req == null ? null : req.itemIds());
        ObjectNode out = board(id);
        out.put("started", started.size());
        return out;
    }

    // ---------------- deliveries ----------------

    public record DeliveryRequest(boolean onlyComplete, boolean includeOriginals) {}

    @PostMapping("/{id}/deliveries")
    public ResponseEntity<Map<String, Object>> deliver(@PathVariable String id, @RequestBody(required = false) DeliveryRequest req,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        long uid = CurrentUser.id();
        SpecProject p = specs.require(uid, id);
        if (specs.roster(p) == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请先导入名单");
        if (specs.spec(p).path("slots").isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请先确认材料规范");
        for (TaskEntity t : deliveryTasks(uid, id)) {
            if ("QUEUED".equals(t.getStatus()) || "RUNNING".equals(t.getStatus())) throw new ApiException(ErrorCode.DELIVERY_RUNNING);
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("projectId", id);
        params.put("onlyComplete", req != null && req.onlyComplete());
        params.put("includeOriginals", req != null && req.includeOriginals());
        List<String> inputs = new ArrayList<>();
        if (p.getDocxTemplateFileId() != null) inputs.add(p.getDocxTemplateFileId());
        if (p.getXlsxTemplateFileId() != null) inputs.add(p.getXlsxTemplateFileId());
        TaskEntity t = engine.submit(uid, "spec.deliver", params, inputs, "SPEC", key);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("taskId", t.getId(), "status", t.getStatus(), "statusUrl", "/api/tasks/" + t.getId()));
    }

    @GetMapping("/{id}/deliveries")
    public List<TaskView> deliveries(@PathVariable String id) {
        long uid = CurrentUser.id();
        specs.require(uid, id);
        return deliveryTasks(uid, id).stream().map(engine::toView).toList();
    }

    private List<TaskEntity> deliveryTasks(long uid, String projectId) {
        List<TaskEntity> out = new ArrayList<>();
        for (TaskEntity t : tasks.findTop50ByOwnerIdAndTypeOrderByCreatedAtDesc(uid, "spec.deliver")) {
            try {
                if (projectId.equals(mapper.readTree(t.getParamsJson()).path("projectId").asText())) out.add(t);
            } catch (Exception ignored) {
                // unreadable params: not ours
            }
        }
        return out;
    }

    // ---------------- view ----------------

    private ObjectNode view(SpecProject p) {
        long uid = p.getOwnerId();
        ObjectNode n = mapper.createObjectNode();
        n.put("id", p.getId());
        n.put("name", p.getName());
        n.put("version", p.getVersion());
        if (p.getNoticeText() != null) n.put("noticeText", p.getNoticeText());
        n.set("spec", specs.spec(p));
        JsonNode roster = specs.roster(p);
        if (roster != null) n.set("roster", roster);
        n.set("ai", mapper.valueToTree(extractor.describe()));
        // only meaningful once a roster exists: before that every non-builtin token is "unknown"
        if (roster != null) {
            n.set("namingUnknown", mapper.valueToTree(SpecSchema.unknownTokens(
                    specs.spec(p).path("naming").path("pattern").asText(null), specs.rosterFields(p))));
        }
        n.put("updatedAt", p.getUpdatedAt());

        // template readiness: which placeholders / columns resolve against the roster and the spec
        Set<String> known = new LinkedHashSet<>(List.of("序号", "状态", "项目名称"));
        if (roster != null) roster.path("fields").forEach(f -> known.add(f.asText()));
        specs.spec(p).path("slots").forEach(s -> known.add(s.path("label").asText()));
        ObjectNode tpl = n.putObject("templates");
        if (p.getDocxTemplateFileId() != null) {
            ObjectNode d = tpl.putObject("docx");
            d.put("fileId", p.getDocxTemplateFileId());
            storage.find(uid, p.getDocxTemplateFileId()).ifPresentOrElse(f -> {
                d.put("filename", f.getOriginalName());
                try {
                    Set<String> ph = TemplateFiller.docxPlaceholders(storage.pathOf(f));
                    d.set("placeholders", mapper.valueToTree(ph));
                    d.set("unresolved", mapper.valueToTree(ph.stream().filter(x -> !known.contains(x)).toList()));
                } catch (ApiException e) {
                    d.put("error", e.getMessage());
                }
            }, () -> d.put("error", "模板文件已不存在"));
        }
        if (p.getXlsxTemplateFileId() != null) {
            ObjectNode x = tpl.putObject("xlsx");
            x.put("fileId", p.getXlsxTemplateFileId());
            if (p.getXlsxHeaderRow() != null) x.put("headerRow", p.getXlsxHeaderRow());
            storage.find(uid, p.getXlsxTemplateFileId()).ifPresentOrElse(f -> {
                x.put("filename", f.getOriginalName());
                try {
                    TemplateFiller.SheetPlan plan = TemplateFiller.planXlsx(storage.pathOf(f), p.getXlsxHeaderRow(), known);
                    x.put("detectedHeaderRow", plan.headerRow() + 1);
                    x.set("columns", mapper.valueToTree(plan.columns().values()));
                } catch (ApiException e) {
                    x.put("error", e.getMessage());
                }
            }, () -> x.put("error", "模板文件已不存在"));
        }
        tpl.set("knownFields", mapper.valueToTree(known));
        return n;
    }
}
