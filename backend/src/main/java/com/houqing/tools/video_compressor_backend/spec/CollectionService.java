package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import com.houqing.tools.video_compressor_backend.spec.CollectionEntities.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** An anonymous link is a write-only capability: no roster, library IDs or download tickets leave this service. */
@Service
public class CollectionService {
    static final int MAX_FILES = 500;
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    private final CollectionRepository links;
    private final SubmissionRepository submissions;
    private final SpecProjectRepository projects;
    private final SpecItemRepository items;
    private final SpecService specs;
    private final UploadService uploads;
    private final StorageService storage;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final SpecFormService forms;
    private final SecureRandom random = new SecureRandom();

    public CollectionService(CollectionRepository links, SubmissionRepository submissions, SpecProjectRepository projects,
                             SpecItemRepository items, SpecService specs, UploadService uploads, StorageService storage,
                             ObjectMapper mapper, org.springframework.transaction.PlatformTransactionManager tm, SpecFormService forms) {
        this.links = links; this.submissions = submissions; this.projects = projects; this.items = items;
        this.specs = specs; this.uploads = uploads; this.storage = storage; this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.forms = forms;
    }

    public List<ObjectNode> list(long uid, String projectId) {
        specs.require(uid, projectId);
        return links.findByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::view).toList();
    }

    public ObjectNode create(long uid, String projectId, int days) {
        if (days < 1 || days > 30) throw invalid("有效期需为 1-30 天");
        return tx.execute(status -> {
            var p = specs.lockProject(uid, projectId);
            if (specs.spec(p).path("slots").isEmpty() && forms.configFor(projectId).path("fields").isEmpty())
                throw invalid("请先保存材料规范，或设置在线收集表的字段");
            // One active link per project; regeneration immediately invalidates older links.
            for (CollectionLink old : links.findByProjectIdOrderByCreatedAtDesc(projectId)) {
                old.setEnabled(0); links.save(old);
            }
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            CollectionLink link = new CollectionLink();
            link.setId(UUID.randomUUID().toString()); link.setProjectId(projectId);
            link.setTokenHash(hash(token)); link.setEnabled(1);
            link.setCreatedAt(System.currentTimeMillis());
            link.setExpiresAt(link.getCreatedAt() + Duration.ofDays(days).toMillis());
            links.save(link);
            return view(link).put("path", "/collect/" + token);
        });
    }

    public void revoke(long uid, String projectId, String linkId) {
        tx.executeWithoutResult(status -> {
            specs.lockProject(uid, projectId);
            CollectionLink link = links.findById(linkId).filter(l -> l.getProjectId().equals(projectId))
                    .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
            link.setEnabled(0); links.save(link);
        });
    }

    private ObjectNode view(CollectionLink link) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", link.getId()); n.put("active", link.getEnabled() == 1 && link.getExpiresAt() > System.currentTimeMillis());
        n.put("expiresAt", link.getExpiresAt()); n.put("createdAt", link.getCreatedAt());
        n.put("files", link.getUsedFiles()); n.put("bytes", link.getUsedBytes());
        n.put("maxFiles", MAX_FILES); n.put("maxBytes", MAX_BYTES);
        return n;
    }

    public ObjectNode publicView(String token) {
        CollectionLink link = requireToken(token);
        var p = projects.findById(link.getProjectId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        ObjectNode n = mapper.createObjectNode().put("name", p.getName()).put("expiresAt", link.getExpiresAt())
                .put("maxFileBytes", MAX_FILE_BYTES);
        var slots = n.putArray("slots");
        if (!forms.configFor(p.getId()).path("fields").isEmpty()) {
            n.set("form", forms.configFor(p.getId()));
            n.put("formKey", p.getId());
        }
        for (JsonNode s : specs.spec(p).path("slots")) {
            ObjectNode slot = slots.addObject().put("key", s.path("key").asText()).put("label", s.path("label").asText())
                    .put("kind", s.path("kind").asText()).put("required", s.path("required").asBoolean());
            slot.set("rules", s.path("rules")); // no notice, naming fields, roster or evidence sentences
        }
        return n;
    }

    public ObjectNode submit(String token, String name, String identifier, String slotKey, String requestKey, MultipartFile file) {
        return submit(token, name, identifier, slotKey, requestKey, file, null, SpecFormService.Access.shared(token, null, null));
    }

    public ObjectNode submit(String token, String name, String identifier, String slotKey, String requestKey, MultipartFile file,
                             String rowId, SpecFormService.Access access) {
        CollectionLink initial = requireToken(token);
        if (!forms.configFor(initial.getProjectId()).path("fields").isEmpty() && (rowId == null || rowId.isBlank()))
            throw invalid("请先填写收集表，再为记录添加附件");
        if (rowId != null) name = forms.authorizeUpload(access, rowId);
        String submittedName = clean(name, 80, "姓名", true);
        String submittedId = clean(identifier, 80, "编号", false);
        String key = clean(requestKey, 80, "提交标识", true);
        if (!key.matches("[a-zA-Z0-9_-]{16,80}")) throw invalid("提交标识无效，请刷新后重试");
        Optional<Submission> previous = submissions.findByCollectionIdAndRequestKey(initial.getId(), key);
        if (previous.isPresent()) return receiptForRow(previous.get(), rowId);
        if (file == null || file.isEmpty()) throw invalid("请选择文件");
        if (file.getSize() > MAX_FILE_BYTES) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "收集链接的单个文件不能超过 200MB");
        checkQuota(initial, file.getSize());
        var project = projects.findById(initial.getProjectId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        requireSlot(project, slotKey);
        // Probe outside the DB transaction: video inspection must not hold the single SQLite writer.
        MediaFile uploaded = uploads.upload(project.getOwnerId(), file,
                Set.of(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE, MediaKind.PDF, MediaKind.DOCUMENT));
        try {
            ObjectNode result = tx.execute(status -> {
                var p = specs.lockProject(project.getOwnerId(), project.getId());
                if (rowId != null) forms.authorizeUpload(access, rowId);
                CollectionLink link = requireToken(token); // expiry/revocation/quota can change during upload
                Optional<Submission> duplicate = submissions.findByCollectionIdAndRequestKey(link.getId(), key);
                if (duplicate.isPresent()) return receiptForRow(duplicate.get(), rowId);
                checkQuota(link, uploaded.getSizeBytes());
                JsonNode slot = requireSlot(p, slotKey);
                if (!SpecMatcher.compatible(slot.path("kind").asText(), uploaded.kind())) throw invalid("文件类型与所选材料项不符");
                JsonNode roster = specs.roster(p);
                if (roster == null) {
                    ObjectNode r = mapper.createObjectNode().put("source", "COLLECTION").put("nameField", "姓名").put("idField", "编号")
                            .put("filename", "收集链接登记名单").put("headerRow", 1).put("sheet", "收集名单").put("fileId", "");
                    r.putArray("fields").add("姓名").add("编号"); r.putArray("people"); r.putArray("warnings"); roster = r;
                }
                String person = resolvePerson(roster, submittedName, submittedId);
                if (person == null && "COLLECTION".equals(roster.path("source").asText())) {
                    // A reused identifier with a different name is kept pending, never silently reassigned.
                    boolean conflictingId = !submittedId.isEmpty() && java.util.stream.StreamSupport.stream(roster.path("people").spliterator(), false)
                            .anyMatch(x -> SpecMatcher.norm(x.path("values").path("编号").asText()).equals(SpecMatcher.norm(submittedId)));
                    if (!conflictingId) {
                        ObjectNode row = ((com.fasterxml.jackson.databind.node.ArrayNode) roster.path("people")).addObject();
                        person = UUID.randomUUID().toString(); row.put("key", person).put("row", roster.path("people").size() + 1);
                        row.putObject("values").put("姓名", submittedName).put("编号", submittedId);
                    }
                }
                p.setRosterJson(roster.toString()); projects.saveAndFlush(p);
                var it = specs.attach(p.getOwnerId(), p.getId(), List.of(new SpecService.Attach(uploaded.getId(), uploaded.getOriginalName()))).get(0);
                Submission submission = new Submission();
                submission.setId(UUID.randomUUID().toString()); submission.setCollectionId(link.getId()); submission.setRequestKey(key);
                submission.setSubmittedBy(submittedName); submission.setIdentifier(submittedId);
                submission.setFilename(uploaded.getOriginalName()); submission.setCreatedAt(System.currentTimeMillis());
                submissions.save(submission);
                it.setPersonKey(person); it.setSlotKey(slotKey); it.setMatchMethod("SUBMISSION");
                it.setMatchNote(person == null ? "链接提交，身份待负责人确认" : "按提交姓名、编号及所选材料项登记");
                it.setSubmittedBy(submittedName); it.setSubmittedIdentifier(submittedId); it.setSubmissionId(submission.getId());
                it.setFormRowId(rowId);
                specs.applyStatus(it, specs.spec(p), uploaded, true); items.save(it);
                if (rowId != null) forms.attached(access, rowId, uploaded.getOriginalName());
                link.setUsedFiles(link.getUsedFiles() + 1); link.setUsedBytes(link.getUsedBytes() + uploaded.getSizeBytes()); links.save(link);
                return receipt(submission);
            });
            // A concurrent retry may already have committed the same submission.
            if (items.findByProjectIdAndFileId(project.getId(), uploaded.getId()).isEmpty()) storage.deleteFile(uploaded);
            return result;
        } catch (RuntimeException e) {
            storage.deleteFile(uploaded);
            throw e;
        }
    }

    static String resolvePerson(JsonNode roster, String name, String identifier) {
        if (roster == null) return null;
        List<JsonNode> matches = new ArrayList<>();
        String nf = roster.path("nameField").asText(), idf = roster.path("idField").asText();
        for (JsonNode p : roster.path("people")) {
            if (!SpecMatcher.norm(p.path("values").path(nf).asText()).equals(SpecMatcher.norm(name))) continue;
            if (identifier != null && !identifier.isBlank() && !idf.isBlank()
                    && !SpecMatcher.norm(p.path("values").path(idf).asText()).equals(SpecMatcher.norm(identifier))) continue;
            matches.add(p);
        }
        return matches.size() == 1 ? matches.get(0).path("key").asText() : null;
    }

    private JsonNode requireSlot(SpecEntities.SpecProject p, String key) {
        JsonNode slot = SpecSchema.slotsByKey(specs.spec(p)).get(key);
        if (slot == null) throw invalid("材料项已变更，请刷新收集页面后重试");
        return slot;
    }
    private void checkQuota(CollectionLink link, long size) {
        if (link.getUsedFiles() >= MAX_FILES || link.getUsedBytes() + size > MAX_BYTES)
            throw new ApiException(ErrorCode.RATE_LIMITED, "本收集链接已达到容量上限，请联系负责人");
    }
    private CollectionLink requireToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw unavailable();
        return links.findByTokenHash(hash(token)).filter(l -> l.getEnabled() == 1 && l.getExpiresAt() > System.currentTimeMillis())
                .orElseThrow(CollectionService::unavailable);
    }
    private ObjectNode receipt(Submission s) {
        return mapper.createObjectNode().put("receipt", s.getId()).put("filename", s.getFilename()).put("submittedAt", s.getCreatedAt());
    }
    private ObjectNode receiptForRow(Submission submission, String rowId) {
        if (rowId != null && items.findBySubmissionId(submission.getId()).filter(i -> rowId.equals(i.getFormRowId())).isEmpty())
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "此提交标识已用于其他记录，请重新选择附件");
        return receipt(submission);
    }
    private static String clean(String value, int max, String label, boolean required) {
        String s = value == null ? "" : value.trim();
        if ((required && s.isEmpty()) || s.length() > max || s.chars().anyMatch(Character::isISOControl)) throw invalid(label + "填写不正确");
        return s;
    }
    private static ApiException invalid(String message) { return new ApiException(ErrorCode.VALIDATION_FAILED, message); }
    private static ApiException unavailable() { return new ApiException(ErrorCode.NOT_FOUND, "收集链接不存在、已到期或已停止收集"); }
    static String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
