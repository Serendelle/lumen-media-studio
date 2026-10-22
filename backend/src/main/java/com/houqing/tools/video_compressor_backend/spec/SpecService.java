package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.spec.SpecEntities.SpecItem;
import com.houqing.tools.video_compressor_backend.spec.SpecEntities.SpecProject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;

/**
 * The material spec desk: a project holds the confirmed spec, the roster and the templates; files dropped into it
 * become items that are matched (person × material), checked against the spec, and optionally fixed by ordinary
 * tasks. AI proposals are validated before being applied; checks and delivery remain deterministic.
 */
@Service
public class SpecService {
    static final Duration PROJECT_RETENTION = Duration.ofDays(30);
    static final int MAX_ITEMS = 2000;

    private final SpecProjectRepository projects;
    private final SpecItemRepository items;
    private final StorageService storage;
    private final SpecChecker checker;
    private final TaskEngine engine;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate db;
    private final com.houqing.tools.video_compressor_backend.collaboration.ProjectAccessService collaboration;

    /** engine is lazy: TaskEngine collects every handler, and the delivery handler depends on this service. */
    public SpecService(SpecProjectRepository projects, SpecItemRepository items, StorageService storage,
                       SpecChecker checker, @org.springframework.context.annotation.Lazy TaskEngine engine, ObjectMapper mapper,
                       org.springframework.jdbc.core.JdbcTemplate db,
                       com.houqing.tools.video_compressor_backend.collaboration.ProjectAccessService collaboration) {
        this.projects = projects;
        this.items = items;
        this.storage = storage;
        this.checker = checker;
        this.engine = engine;
        this.mapper = mapper;
        this.db = db;
        this.collaboration = collaboration;
    }

    // ---------------- projects ----------------

    public SpecProject create(long uid, String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 2 || n.length() > 80) throw new ApiException(ErrorCode.VALIDATION_FAILED, "项目名称需为 2-80 个字");
        SpecProject p = new SpecProject();
        p.setId(UUID.randomUUID().toString());
        p.setOwnerId(uid);
        p.setName(n);
        ObjectNode spec = mapper.createObjectNode();
        spec.put("title", n);
        spec.put("sizeUnit", "DECIMAL");
        spec.putArray("slots");
        spec.putObject("naming").put("pattern", "{序号}_{姓名}_{材料}");
        spec.putArray("notes");
        p.setSpecJson(spec.toString());
        p.setVersion(1);
        long now = System.currentTimeMillis();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return projects.saveAndFlush(p);
    }

    public SpecProject require(long uid, String id) {
        return projects.findByIdAndOwnerId(id, uid).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "项目不存在或无权访问"));
    }

    /** Serialize project mutations in both SQLite and PostgreSQL. Caller must have a transaction. */
    SpecProject lockProject(long uid, String id) {
        require(uid, id);
        projects.lockProject(id);
        return require(uid, id);
    }

    public Page<SpecProject> list(long uid, int page, int size) {
        return projects.findByOwnerId(uid, PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    @Transactional
    public void delete(long uid, String id) {
        SpecProject p = lockProject(uid, id);
        // the files stay in the user's library; they just fall back to the normal retention
        for (SpecItem it : items.findByProjectIdOrderByCreatedAtAsc(id)) {
            release(uid, it.getFileId());
            release(uid, it.getFixedFileId());
        }
        release(uid, p.getDocxTemplateFileId());
        release(uid, p.getXlsxTemplateFileId());
        List<String> sharedFiles = db.queryForList("select file_id from project_file_versions where project_id=?", String.class, id);
        items.deleteByProjectId(id);
        projects.delete(p);
        projects.flush(); // Cascades release project references before ordinary library retention can resume.
        for (String fileId : sharedFiles) release(uid, fileId);
    }

    // ---------------- spec ----------------

    public JsonNode spec(SpecProject p) { return read(p.getSpecJson()); }

    public JsonNode roster(SpecProject p) { return p.getRosterJson() == null ? null : read(p.getRosterJson()); }

    public List<String> rosterFields(SpecProject p) {
        JsonNode r = roster(p);
        if (r == null) return null;
        List<String> f = new ArrayList<>();
        r.path("fields").forEach(x -> f.add(x.asText()));
        return f;
    }

    /** Saves a confirmed spec (user edits or an accepted draft). Invalid specs are refused with every reason. */
    @Transactional
    public SchemaReport saveSpec(long uid, String id, JsonNode spec, int expectedVersion, String noticeText) {
        SpecProject p = lockProject(uid, id);
        if (expectedVersion != p.getVersion()) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        if (noticeText != null) {
            if (noticeText.length() > 20000) throw new ApiException(ErrorCode.VALIDATION_FAILED, "通知原文不能超过 20000 字");
            p.setNoticeText(noticeText);
        }
        SpecSchema.Result r = SpecSchema.validate(spec, mapper, p.getNoticeText(), rosterFields(p));
        if (!r.valid()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "规范不完整：" + r.errors().get(0),
                    Map.of("errors", r.errors(), "warnings", r.warnings()));
        }
        // keep the spec title in step with the project name when it is left empty
        if (r.spec().path("title").asText().isEmpty()) r.spec().put("title", p.getName());
        p.setSpecJson(r.spec().toString());
        p.setVersion(p.getVersion() + 1);
        p.setUpdatedAt(System.currentTimeMillis());
        projects.save(p);
        rematchAndRecheck(p, true);
        db.update("update spec_projects set publication_status='DRAFT',team_version=team_version+1 where id=?",id);
        collaboration.activity(collaboration.require(uid,id),"SPEC_UPDATED",null);
        return new SchemaReport(r.spec(), r.warnings(), p.getVersion());
    }

    public record SchemaReport(JsonNode spec, List<String> warnings, int version) {}

    // ---------------- roster & templates ----------------

    @Transactional
    public JsonNode importRoster(long uid, String id, String fileId, Integer headerRow, String nameField) {
        SpecProject p = lockProject(uid, id);
        MediaFile f = storage.require(uid, fileId);
        if (f.kind() != MediaKind.DOCUMENT || !"xlsx".equals(StorageService.extensionOf(f.getStoredName()))) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "名单需为 .xlsx 文件");
        }
        ObjectNode roster = RosterParser.parse(storage.pathOf(f), headerRow, nameField, mapper);
        roster.put("fileId", fileId);
        roster.put("filename", f.getOriginalName());
        p.setRosterJson(roster.toString());
        p.setUpdatedAt(System.currentTimeMillis());
        projects.save(p);
        keep(f);
        rematchAndRecheck(p, false);
        return roster;
    }

    @Transactional
    public void setTemplates(long uid, String id, String docxFileId, String xlsxFileId, Integer xlsxHeaderRow) {
        SpecProject p = lockProject(uid, id);
        if (docxFileId != null && !docxFileId.isBlank()) {
            MediaFile f = storage.require(uid, docxFileId);
            if (!"docx".equals(StorageService.extensionOf(f.getStoredName()))) throw new ApiException(ErrorCode.VALIDATION_FAILED, "个人表模板需为 .docx");
            p.setDocxTemplateFileId(docxFileId);
            keep(f);
        } else p.setDocxTemplateFileId(null);
        if (xlsxFileId != null && !xlsxFileId.isBlank()) {
            MediaFile f = storage.require(uid, xlsxFileId);
            if (!"xlsx".equals(StorageService.extensionOf(f.getStoredName()))) throw new ApiException(ErrorCode.VALIDATION_FAILED, "汇总表模板需为 .xlsx");
            p.setXlsxTemplateFileId(xlsxFileId);
            keep(f);
        } else p.setXlsxTemplateFileId(null);
        if (xlsxHeaderRow != null && (xlsxHeaderRow < 1 || xlsxHeaderRow > 1000)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "表头行需为 1-1000");
        p.setXlsxHeaderRow(xlsxHeaderRow);
        p.setUpdatedAt(System.currentTimeMillis());
        projects.save(p);
    }

    // ---------------- items ----------------

    public record Attach(String fileId, String relativePath) {}

    @Transactional
    public List<SpecItem> attach(long uid, String id, List<Attach> files) {
        SpecProject p = lockProject(uid, id);
        if (items.countByProjectId(id) + files.size() > MAX_ITEMS) throw new ApiException(ErrorCode.VALIDATION_FAILED, "一个项目最多 " + MAX_ITEMS + " 个文件");
        JsonNode spec = spec(p), roster = roster(p);
        List<SpecItem> out = new ArrayList<>();
        for (Attach a : files) {
            MediaFile f = storage.require(uid, a.fileId());
            if (items.findByProjectIdAndFileId(id, f.getId()).isPresent()) continue;
            String path = cleanPath(a.relativePath(), f.getOriginalName());
            SpecItem it = new SpecItem();
            it.setId(UUID.randomUUID().toString());
            it.setProjectId(id);
            it.setOwnerId(uid);
            it.setFileId(f.getId());
            it.setRelativePath(path);
            long now = System.currentTimeMillis();
            it.setCreatedAt(now);
            it.setUpdatedAt(now);
            it.setMatchMethod("NONE");
            SpecMatcher.Match m = SpecMatcher.match(path, f.kind(), spec, roster);
            it.setPersonKey(m.personKey());
            it.setSlotKey(m.slotKey());
            it.setMatchNote(m.note());
            if (m.personKey() != null || m.slotKey() != null) it.setMatchMethod("AUTO");
            recheck(it, spec, f);
            out.add(items.save(it));
            keep(f);
        }
        p.setUpdatedAt(System.currentTimeMillis());
        projects.save(p);
        return out;
    }

    @Transactional
    public SpecItem assign(long uid, String id, String itemId, String personKey, String slotKey) {
        SpecProject p = lockProject(uid, id);
        SpecItem it = items.findByIdAndProjectId(itemId, id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "文件不在该项目中"));
        JsonNode roster = roster(p), spec = spec(p);
        if (personKey != null && !personKey.isEmpty()) {
            boolean ok = roster != null && java.util.stream.StreamSupport.stream(roster.path("people").spliterator(), false)
                    .anyMatch(x -> x.path("key").asText().equals(personKey));
            if (!ok) throw new ApiException(ErrorCode.VALIDATION_FAILED, "名单中没有这个人");
        }
        if (slotKey != null && !slotKey.isEmpty() && !SpecSchema.slotsByKey(spec).containsKey(slotKey)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "规范中没有这个材料项");
        }
        boolean slotChanged = !Objects.equals(emptyToNull(slotKey), it.getSlotKey());
        it.setPersonKey(emptyToNull(personKey));
        it.setSlotKey(emptyToNull(slotKey));
        it.setMatchMethod("MANUAL");
        it.setMatchNote("手动指定");
        applyStatus(it, spec, storage.require(uid, it.getFileId()), slotChanged);
        it.setUpdatedAt(System.currentTimeMillis());
        return items.save(it);
    }

    @Transactional
    public void removeItem(long uid, String id, String itemId) {
        lockProject(uid, id);
        SpecItem it = items.findByIdAndProjectId(itemId, id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "文件不在该项目中"));
        release(uid, it.getFileId());
        release(uid, it.getFixedFileId());
        items.delete(it);
    }

    /** Current items, after folding in the outcome of any fix task that finished since the last look. */
    public List<SpecItem> items(long uid, String id) {
        SpecProject p = require(uid, id);
        JsonNode spec = spec(p);
        List<SpecItem> list = items.findByProjectIdOrderByCreatedAtAsc(id);
        for (SpecItem it : list) {
            if ("FIXING".equals(it.getStatus()) && it.getFixTaskId() != null) refreshFix(uid, it, spec);
        }
        return list;
    }

    private void refreshFix(long uid, SpecItem it, JsonNode spec) {
        TaskEntity t;
        try {
            t = engine.require(uid, it.getFixTaskId());
        } catch (ApiException e) {
            setFixFailed(it, "修正任务已不存在");
            return;
        }
        switch (t.getStatus()) {
            case "SUCCEEDED" -> {
                Optional<MediaFile> out = t.getOutputFile() == null ? Optional.empty() : storage.find(uid, t.getOutputFile());
                JsonNode slot = SpecSchema.slotsByKey(spec).get(it.getSlotKey());
                if (out.isEmpty() || slot == null) {
                    setFixFailed(it, "修正结果不可用");
                    return;
                }
                keep(out.get());
                // the fixed file is checked against the very same rules as an upload would be
                ObjectNode c = checker.check(out.get(), slot);
                ObjectNode check = (ObjectNode) read(it.getCheckJson());
                check.set("afterFix", c);
                it.setCheckJson(check.toString());
                it.setFixedFileId(out.get().getId());
                it.setStatus("OK".equals(c.path("status").asText()) ? "FIXED" : "FIX_FAILED");
                it.setUpdatedAt(System.currentTimeMillis());
                items.save(it);
            }
            case "FAILED", "CANCELLED" -> setFixFailed(it, t.getMessage());
            default -> { /* still running */ }
        }
    }

    private void setFixFailed(SpecItem it, String why) {
        ObjectNode check = (ObjectNode) read(it.getCheckJson());
        check.put("fixError", why == null ? "修正失败" : why);
        it.setCheckJson(check.toString());
        it.setStatus("FIX_FAILED");
        it.setUpdatedAt(System.currentTimeMillis());
        items.save(it);
    }

    /** Submits one fix task per fixable item (all fixable items when itemIds is empty). */
    public List<SpecItem> fix(long uid, String id, List<String> itemIds) {
        SpecProject p = require(uid, id);
        Set<String> only = itemIds == null ? Set.of() : new HashSet<>(itemIds);
        List<SpecItem> started = new ArrayList<>();
        for (SpecItem it : items.findByProjectIdOrderByCreatedAtAsc(id)) {
            if (!"FIXABLE".equals(it.getStatus()) || (!only.isEmpty() && !only.contains(it.getId()))) continue;
            JsonNode fix = read(it.getCheckJson()).path("fix");
            TaskEntity t = engine.submit(uid, fix.path("type").asText(), fix.path("params"), List.of(it.getFileId()),
                    "SPEC", "spec-fix:" + it.getId() + ":" + it.getUpdatedAt());
            it.setFixTaskId(t.getId());
            it.setStatus("FIXING");
            it.setUpdatedAt(System.currentTimeMillis());
            started.add(items.save(it));
        }
        p.setUpdatedAt(System.currentTimeMillis());
        projects.save(p);
        return started;
    }

    // ---------------- matching / checking ----------------

    /**
     * After the spec or roster changed: re-match automatic items (manual choices stay). A spec change re-checks
     * everything and drops earlier fixes (the rules moved); a roster change only re-assigns people and keeps fixes.
     */
    private void rematchAndRecheck(SpecProject p, boolean specChanged) {
        JsonNode spec = spec(p), roster = roster(p);
        Map<String, JsonNode> slots = SpecSchema.slotsByKey(spec);
        for (SpecItem it : items.findByProjectIdOrderByCreatedAtAsc(p.getId())) {
            Optional<MediaFile> f = storage.find(p.getOwnerId(), it.getFileId());
            if (f.isEmpty()) continue;
            String oldSlot = it.getSlotKey();
            if ("SUBMISSION".equals(it.getMatchMethod())) {
                it.setPersonKey(CollectionService.resolvePerson(roster, it.getSubmittedBy(), it.getSubmittedIdentifier()));
                if (!slots.containsKey(it.getSlotKey())) it.setSlotKey(null);
            } else if (!"MANUAL".equals(it.getMatchMethod()) && !"AI".equals(it.getMatchMethod())) {
                SpecMatcher.Match m = SpecMatcher.match(it.getRelativePath(), f.get().kind(), spec, roster);
                it.setPersonKey(m.personKey());
                it.setSlotKey(m.slotKey());
                it.setMatchNote(m.note());
                it.setMatchMethod(m.personKey() != null || m.slotKey() != null ? "AUTO" : "NONE");
            } else if (it.getSlotKey() != null && !slots.containsKey(it.getSlotKey())) {
                it.setSlotKey(null); // the slot was removed from the spec
            }
            if (it.getPersonKey() != null && (roster == null || java.util.stream.StreamSupport.stream(roster.path("people").spliterator(), false)
                    .noneMatch(x -> x.path("key").asText().equals(it.getPersonKey())))) it.setPersonKey(null);
            applyStatus(it, spec, f.get(), specChanged || !Objects.equals(oldSlot, it.getSlotKey()));
            it.setUpdatedAt(System.currentTimeMillis());
            items.save(it);
        }
    }

    /** Recomputes the status; with resetFix=false a fix already made for this slot is kept and reflected. */
    void applyStatus(SpecItem it, JsonNode spec, MediaFile f, boolean resetFix) {
        if (resetFix || it.getFixTaskId() == null) {
            it.setFixedFileId(null);
            it.setFixTaskId(null);
            recheck(it, spec, f);
            return;
        }
        if (it.getPersonKey() == null || it.getSlotKey() == null) {
            it.setStatus("UNMATCHED");
        } else if (it.getFixedFileId() == null) {
            it.setStatus("FIXING");
        } else {
            boolean ok = "OK".equals(read(it.getCheckJson()).path("afterFix").path("status").asText());
            it.setStatus(ok ? "FIXED" : "FIX_FAILED");
        }
    }

    private void recheck(SpecItem it, JsonNode spec, MediaFile f) {
        JsonNode slot = it.getSlotKey() == null ? null : SpecSchema.slotsByKey(spec).get(it.getSlotKey());
        if (slot == null) {
            it.setStatus("UNMATCHED");
            it.setCheckJson(null);
            return;
        }
        ObjectNode c = checker.check(f, slot);
        it.setCheckJson(c.toString());
        it.setStatus(it.getPersonKey() == null ? "UNMATCHED" : c.path("status").asText());
    }

    // ---------------- views ----------------

    /** People × slots: for each cell the item that would be delivered, plus conflicts and gaps. */
    public ObjectNode board(SpecProject p, List<SpecItem> list) {
        JsonNode spec = spec(p), roster = roster(p);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode rows = out.putArray("people");
        Map<String, List<SpecItem>> byCell = new HashMap<>();
        for (SpecItem it : list) {
            if (it.getPersonKey() != null && it.getSlotKey() != null) {
                byCell.computeIfAbsent(it.getPersonKey() + "|" + it.getSlotKey(), k -> new ArrayList<>()).add(it);
            }
        }
        int complete = 0, people = 0;
        if (roster != null) {
            String nameField = roster.path("nameField").asText();
            for (JsonNode person : roster.path("people")) {
                people++;
                ObjectNode row = rows.addObject();
                String key = person.path("key").asText();
                row.put("key", key);
                row.put("name", person.path("values").path(nameField).asText());
                row.put("row", person.path("row").asInt());
                ObjectNode cells = row.putObject("cells");
                boolean ok = true;
                for (JsonNode slot : spec.path("slots")) {
                    String sk = slot.path("key").asText();
                    List<SpecItem> c = byCell.getOrDefault(key + "|" + sk, List.of());
                    ObjectNode cell = cells.putObject(sk);
                    SpecItem best = best(c);
                    cell.put("count", c.size());
                    if (best != null) {
                        cell.put("itemId", best.getId());
                        cell.put("status", best.getStatus());
                    } else cell.put("status", "MISSING");
                    boolean usable = best != null && usable(best.getStatus());
                    if (slot.path("required").asBoolean(true) && !usable) ok = false;
                }
                row.put("complete", ok);
                if (ok) complete++;
            }
        }
        ObjectNode sum = out.putObject("summary");
        sum.put("people", people);
        sum.put("complete", complete);
        sum.put("files", list.size());
        Map<String, Integer> byStatus = new TreeMap<>();
        list.forEach(it -> byStatus.merge(it.getStatus(), 1, Integer::sum));
        ObjectNode st = sum.putObject("byStatus");
        byStatus.forEach(st::put);
        return out;
    }

    /** The file a cell delivers: a verified fix or an upload that already met the spec, newest first. */
    public static SpecItem best(List<SpecItem> c) {
        SpecItem best = null;
        for (SpecItem it : c) {
            if (best == null || rank(it) > rank(best) || (rank(it) == rank(best) && it.getCreatedAt() > best.getCreatedAt())) best = it;
        }
        return best;
    }

    static int rank(SpecItem it) {
        return switch (it.getStatus()) {
            case "OK", "FIXED" -> 5;
            case "FIXING" -> 4;
            case "FIXABLE" -> 3;
            case "FIX_FAILED", "INVALID" -> 2;
            default -> 1;
        };
    }

    public static boolean usable(String status) { return "OK".equals(status) || "FIXED".equals(status); }

    public ObjectNode itemView(SpecItem it, long uid) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", it.getId());
        n.put("fileId", it.getFileId());
        n.put("relativePath", it.getRelativePath());
        storage.find(uid, it.getFileId()).ifPresent(f -> {
            n.put("filename", f.getOriginalName());
            n.put("mediaType", f.getMediaType());
            n.put("sizeBytes", f.getSizeBytes());
        });
        if (it.getPersonKey() != null) n.put("personKey", it.getPersonKey());
        if (it.getSlotKey() != null) n.put("slotKey", it.getSlotKey());
        n.put("matchMethod", it.getMatchMethod());
        if (it.getMatchNote() != null) n.put("matchNote", it.getMatchNote());
        if (it.getSubmittedBy() != null) n.put("submittedBy", it.getSubmittedBy());
        if (it.getSubmissionId() != null) n.put("submittedAt", it.getCreatedAt());
        n.put("status", it.getStatus());
        if (it.getCheckJson() != null) n.set("check", read(it.getCheckJson()));
        if (it.getFixedFileId() != null) n.put("fixedFileId", it.getFixedFileId());
        if (it.getFixTaskId() != null) n.put("fixTaskId", it.getFixTaskId());
        return n;
    }

    // ---------------- helpers ----------------

    private void keep(MediaFile f) {
        storage.keepUntil(f, System.currentTimeMillis() + PROJECT_RETENTION.toMillis());
    }

    private void release(long uid, String fileId) {
        if (fileId != null) storage.find(uid, fileId).ifPresent(storage::releaseRetention);
    }

    /** Keeps only the folder structure (no drive letters, no "..") — it is evidence for matching, never a path on disk. */
    static String cleanPath(String rel, String fallback) {
        String s = rel == null || rel.isBlank() ? fallback : rel;
        s = s.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String seg : s.split("/")) {
            String t = seg.trim();
            if (t.isEmpty() || t.equals(".") || t.equals("..") || t.endsWith(":")) continue;
            parts.add(t.length() > 120 ? t.substring(0, 120) : t);
        }
        String joined = String.join("/", parts);
        if (joined.isEmpty()) joined = fallback == null ? "file" : fallback;
        return joined.length() > 500 ? joined.substring(joined.length() - 500) : joined;
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    JsonNode read(String json) {
        try {
            return json == null ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
