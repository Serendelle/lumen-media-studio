package com.houqing.tools.video_compressor_backend.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.auth.UserRepository;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.houqing.tools.video_compressor_backend.spec.FormSchema.invalid;

/** All reads, exports, comments and attachments resolve the same current policy, never a cached grant. */
@Service
public class SpecFormService {
    static final int MAX_ROWS = 2000;
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final SpecService specs;
    private final SpecProjectRepository projects;
    private final CollectionRepository links;
    private final UserRepository users;
    private final SpecItemRepository items;
    private final StorageService storage;
    private final UploadService uploads;
    private final TransactionTemplate tx;

    public SpecFormService(JdbcTemplate db, ObjectMapper mapper, SpecService specs, SpecProjectRepository projects,
                           CollectionRepository links, UserRepository users, SpecItemRepository items, StorageService storage,
                           org.springframework.transaction.PlatformTransactionManager tm, UploadService uploads) {
        this.db = db; this.mapper = mapper; this.specs = specs; this.projects = projects; this.links = links;
        this.users = users; this.items = items; this.storage = storage; this.tx = new TransactionTemplate(tm);
        this.uploads = uploads;
    }

    public record Access(String projectId, String token, Long userId, String secret) {
        public static Access owner(String projectId, long uid) { return new Access(projectId, null, uid, null); }
        public static Access shared(String token, Long uid, String secret) { return new Access(null, token, uid, secret); }
    }
    record Actor(String key, String name, boolean owner, String role) {}
    record Scope(SpecEntities.SpecProject project, ObjectNode config, Actor actor) {}
    record Row(String id, String projectId, String creator, String name, String json, int version, boolean deleted, long createdAt, long updatedAt) {}

    private SpecEntities.SpecProject project(Access access, boolean lock) {
        SpecEntities.SpecProject p;
        if (access.token() == null) p = specs.require(Objects.requireNonNull(access.userId()), access.projectId());
        else {
            String token = access.token();
            if (!token.matches("[A-Za-z0-9_-]{43}")) throw missingLink();
            var link = links.findByTokenHash(CollectionService.hash(token))
                    .filter(l -> l.getEnabled() == 1 && l.getExpiresAt() > System.currentTimeMillis()).orElseThrow(SpecFormService::missingLink);
            p = projects.findById(link.getProjectId()).orElseThrow(SpecFormService::missingLink);
        }
        if (lock) {
            p = specs.lockProject(p.getOwnerId(), p.getId());
            // Revalidate the capability after waiting for another writer (including revocation).
            if (access.token() != null) project(access, false);
        }
        return p;
    }

    public ObjectNode configFor(String projectId) {
        var configs = db.query("select config_json, version from spec_forms where project_id=?",
                (rs, n) -> ((ObjectNode) specs.read(rs.getString(1))).put("version", rs.getInt(2)), projectId);
        return configs.isEmpty() ? FormSchema.defaults(mapper).put("version", 0) : configs.get(0);
    }

    long recordCount(String projectId) {
        return db.queryForObject("select count(*) from spec_form_rows where project_id=? and deleted=0", Long.class, projectId);
    }

    private Scope scope(Access access, boolean lock) {
        var p = project(access, lock);
        return new Scope(p, configFor(p.getId()), actor(access, p));
    }

    private Actor actor(Access access, SpecEntities.SpecProject p) {
        if (access.userId() != null && access.userId() == p.getOwnerId()) {
            return new Actor("owner", users.findById(p.getOwnerId()).orElseThrow().getDisplayName(), true, "OWNER");
        }
        List<Actor> participants;
        if (access.userId() != null) {
            participants = db.query("select id,name from spec_form_participants where project_id=? and user_id=?",
                    (rs, n) -> new Actor(rs.getString(1), rs.getString(2), false, null), p.getId(), access.userId());
        } else if (validSecret(access.secret())) {
            participants = db.query("select id,name from spec_form_participants where project_id=? and secret_hash=? and user_id is null",
                    (rs, n) -> new Actor(rs.getString(1), rs.getString(2), false, null), p.getId(), CollectionService.hash(access.secret()));
        } else participants = List.of();
        if (participants.isEmpty()) throw new ApiException(ErrorCode.UNAUTHORIZED, "请先登记姓名；指定成员请登录后进入");
        Actor a = participants.get(0);
        List<String> role = access.userId() == null ? List.of() : db.query("select role from spec_form_members where project_id=? and user_id=?",
                (rs, n) -> rs.getString(1), p.getId(), access.userId());
        return new Actor(a.key(), a.name(), false, role.isEmpty() ? null : role.get(0));
    }

    public ObjectNode join(Access access, String name, String secret) {
        if (!validSecret(secret)) throw invalid("个人凭证无效，请重新打开页面");
        return tx.execute(status -> {
            var p = project(access, true);
            if (access.userId() != null && access.userId() == p.getOwnerId()) return actorView(actor(access, p));
            if (access.userId() != null && db.queryForObject("select count(*) from spec_form_participants where project_id=? and user_id=?", Long.class, p.getId(), access.userId()) > 0)
                return actorView(actor(access, p));
            String submittedName = access.userId() == null ? FormSchema.text(name, 80, "姓名", true)
                    : users.findById(access.userId()).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED)).getDisplayName();
            String hash = CollectionService.hash(secret);
            var prior = db.queryForList("select id,project_id,user_id from spec_form_participants where secret_hash=?", hash);
            if (!prior.isEmpty()) {
                var previous = prior.get(0);
                if (!p.getId().equals(previous.get("project_id")) || previous.get("user_id") != null)
                    throw new ApiException(ErrorCode.UNAUTHORIZED, "该凭证需要原账号登录，请勿共用个人凭证");
                if (access.userId() != null) db.update("update spec_form_participants set user_id=?,name=? where id=?", access.userId(), submittedName, previous.get("id"));
            } else {
                if (db.queryForObject("select count(*) from spec_form_participants where project_id=?", Long.class, p.getId()) >= 5000)
                    throw new ApiException(ErrorCode.RATE_LIMITED, "参与者数量已达上限");
                db.update("insert into spec_form_participants(id,project_id,user_id,name,secret_hash,created_at) values(?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), p.getId(), access.userId(), submittedName, hash, System.currentTimeMillis());
            }
            return actorView(actor(new Access(null, access.token(), access.userId(), secret), p));
        });
    }

    private ObjectNode actorView(Actor actor) {
        return mapper.createObjectNode().put("name", actor.name()).put("role", actor.owner() ? "OWNER" : actor.role() == null ? "PARTICIPANT" : actor.role());
    }

    public ObjectNode settings(Access access) {
        var p = project(access, false);
        return configFor(p.getId());
    }

    public ObjectNode saveSettings(Access access, JsonNode input, int expectedVersion) {
        ObjectNode normalized = FormSchema.config(input, mapper);
        return tx.execute(status -> {
            Scope s = scope(access, true); requireOwner(s);
            if (s.config().path("version").asInt() != expectedVersion) throw conflict();
            if (db.queryForObject("select count(*) from spec_form_rows where project_id=?", Long.class, s.project().getId()) > 0) {
                Map<String, String> types = new HashMap<>(); normalized.path("fields").forEach(f -> types.put(f.path("key").asText(), f.path("type").asText()));
                for (JsonNode f : s.config().path("fields")) {
                    if (!f.path("type").asText().equals(types.get(f.path("key").asText())))
                        throw invalid("已有记录的字段不能删除或更改类型，可修改名称或新增字段");
                }
            }
            String pid = s.project().getId(); int next = expectedVersion + 1;
            if (expectedVersion == 0) db.update("insert into spec_forms(project_id,config_json,version,updated_at) values(?,?,?,?)", pid, normalized.toString(), next, System.currentTimeMillis());
            else db.update("update spec_forms set config_json=?,version=?,updated_at=? where project_id=?", normalized.toString(), next, System.currentTimeMillis(), pid);
            event(s, null, "SETTINGS", s.config(), normalized);
            return normalized.deepCopy().put("version", next);
        });
    }

    public List<Map<String, Object>> members(Access access) {
        Scope s = scope(access, false); requireOwner(s);
        return db.query("select u.username,u.display_name,m.role from spec_form_members m join users u on u.id=m.user_id where m.project_id=? order by u.username",
                (rs, n) -> Map.<String, Object>of("username", rs.getString(1), "displayName", rs.getString(2), "role", rs.getString(3)), s.project().getId());
    }

    public void member(Access access, String username, String role) {
        if (role != null && !Set.of("EDITOR", "VIEWER").contains(role)) throw invalid("成员角色无效");
        tx.executeWithoutResult(status -> {
            Scope s = scope(access, true); requireOwner(s);
            var user = users.findByUsernameIgnoreCase(FormSchema.text(username, 80, "账号", true))
                    .orElseThrow(() -> invalid("账号不存在，请先让成员注册，再填写其登录账号"));
            if (user.getId() == s.project().getOwnerId()) throw invalid("负责人无需重复添加");
            db.update("delete from spec_form_members where project_id=? and user_id=?", s.project().getId(), user.getId());
            if (role != null) db.update("insert into spec_form_members(project_id,user_id,role) values(?,?,?)", s.project().getId(), user.getId(), role);
            event(s, null, "MEMBER", null, mapper.createObjectNode().put("username", user.getUsername()).put("role", role));
        });
    }

    private boolean seesAll(Scope s) { return s.actor().owner() || s.actor().role() != null || "ALL".equals(s.config().path("viewMode").asText()); }
    private boolean sees(Scope s, Row row) { return seesAll(s) || row.creator().equals(s.actor().key()); }
    private boolean edits(Scope s, Row row) {
        if (s.actor().owner()) return true;
        if (!s.config().path("accepting").asBoolean() || "VIEWER".equals(s.actor().role()) || "LOCKED".equals(s.config().path("editMode").asText()) || !sees(s, row)) return false;
        return "EDITOR".equals(s.actor().role()) || "ALL".equals(s.config().path("editMode").asText())
                || ("OWN".equals(s.config().path("editMode").asText()) && row.creator().equals(s.actor().key()));
    }
    private boolean creates(Scope s) { return s.actor().owner() || (s.config().path("accepting").asBoolean() && !"VIEWER".equals(s.actor().role())); }
    private void requireOwner(Scope s) { if (!s.actor().owner()) throw new ApiException(ErrorCode.FORBIDDEN); }
    private Row row(Scope s, String id, boolean deleted) {
        return db.query("select * from spec_form_rows where id=? and project_id=?", this::mapRow, id, s.project().getId()).stream()
                .filter(r -> r.deleted() == deleted && sees(s, r)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "记录不存在或无权查看"));
    }
    private Row mapRow(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Row(r.getString("id"), r.getString("project_id"), r.getString("creator_key"), r.getString("creator_name"),
                r.getString("values_json"), r.getInt("version"), r.getInt("deleted") == 1, r.getLong("created_at"), r.getLong("updated_at"));
    }
    private List<Row> visibleRows(Scope s, boolean deleted) {
        return seesAll(s) ? db.query("select * from spec_form_rows where project_id=? and deleted=? order by created_at,id", this::mapRow, s.project().getId(), deleted ? 1 : 0)
                : db.query("select * from spec_form_rows where project_id=? and creator_key=? and deleted=? order by created_at,id", this::mapRow, s.project().getId(), s.actor().key(), deleted ? 1 : 0);
    }

    public ObjectNode board(Access access, int page, int size, boolean deleted) {
        Scope s = scope(access, false);
        if (deleted) requireOwner(s);
        size = Math.max(1, Math.min(100, size)); page = Math.max(0, page);
        List<Row> rows = visibleRows(s, deleted);
        ObjectNode out = mapper.createObjectNode().put("total", rows.size()).put("page", page).put("size", size);
        out.set("config", s.config()); out.set("actor", actorView(s.actor()));
        boolean hasOwn = rows.stream().anyMatch(r -> r.creator().equals(s.actor().key()) && !r.deleted());
        out.putObject("permissions").put("seeAll", seesAll(s)).put("create", creates(s) && (s.actor().owner() || s.config().path("allowMultiple").asBoolean() || !hasOwn)).put("owner", s.actor().owner());
        var arr = out.putArray("rows");
        List<SpecEntities.SpecItem> attachments = items.findByProjectIdOrderByCreatedAtAsc(s.project().getId());
        rows.stream().skip((long) page * size).limit(size).forEach(r -> arr.add(rowView(s, r, attachments)));
        return out;
    }

    private ObjectNode rowView(Scope s, Row r, List<SpecEntities.SpecItem> attachments) {
        ObjectNode n = mapper.createObjectNode().put("id", r.id()).put("submittedBy", r.name()).put("version", r.version())
                .put("createdAt", r.createdAt()).put("updatedAt", r.updatedAt()).put("deleted", r.deleted())
                .put("mine", r.creator().equals(s.actor().key())).put("canAttach", !r.deleted() && (edits(s, r) || (creates(s) && r.creator().equals(s.actor().key()))))
                .put("canEdit", !r.deleted() && edits(s, r)).put("canDelete", !r.deleted() && edits(s, r) && (s.actor().owner() || r.creator().equals(s.actor().key())))
                .put("canComment", !r.deleted() && s.config().path("allowComments").asBoolean()
                        && (s.actor().owner() || (s.config().path("accepting").asBoolean() && !"VIEWER".equals(s.actor().role()))));
        n.set("values", specs.read(r.json()));
        var files = n.putArray("files");
        attachments.stream().filter(i -> r.id().equals(i.getFormRowId())).forEach(i -> storage.find(s.project().getOwnerId(), i.getFileId()).ifPresent(f ->
                files.addObject().put("id", i.getId()).put("filename", f.getOriginalName()).put("sizeBytes", f.getSizeBytes()).put("slotKey", i.getSlotKey()).put("status", i.getStatus())));
        return n;
    }

    public ObjectNode create(Access access, JsonNode values, String requestKey, int configVersion) {
        if (requestKey == null || !requestKey.matches("[a-zA-Z0-9_-]{16,80}")) throw invalid("提交标识无效");
        return tx.execute(status -> {
            Scope s = scope(access, true);
            if (!creates(s)) throw new ApiException(ErrorCode.FORBIDDEN, "已停止收集或你只有查看权限");
            if (s.config().path("fields").isEmpty()) throw invalid("负责人尚未设置收集字段");
            var prior = db.query("select * from spec_form_rows where project_id=? and creator_key=? and request_key=?", this::mapRow, s.project().getId(), s.actor().key(), requestKey);
            if (!prior.isEmpty()) {
                if (prior.get(0).deleted()) throw conflict();
                return rowView(s, prior.get(0), items.findByProjectIdOrderByCreatedAtAsc(s.project().getId()));
            }
            if (configVersion != s.config().path("version").asInt()) throw conflict();
            if (!s.actor().owner() && !s.config().path("allowMultiple").asBoolean() && db.queryForObject("select count(*) from spec_form_rows where project_id=? and creator_key=? and deleted=0", Long.class, s.project().getId(), s.actor().key()) > 0)
                throw new ApiException(ErrorCode.VERSION_CONFLICT, "你已经提交过，请修改已有记录");
            if (db.queryForObject("select count(*) from spec_form_rows where project_id=?", Long.class, s.project().getId()) >= MAX_ROWS)
                throw new ApiException(ErrorCode.RATE_LIMITED, "表格最多累计收集 2000 条记录");
            ObjectNode normalized = FormSchema.values(values, s.config(), mapper, true);
            String id = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
            db.update("insert into spec_form_rows(id,project_id,creator_key,creator_name,values_json,version,deleted,request_key,created_at,updated_at) values(?,?,?,?,?,1,0,?,?,?)",
                    id, s.project().getId(), s.actor().key(), s.actor().name(), normalized.toString(), requestKey, now, now);
            event(s, id, "CREATE", null, normalized);
            return rowView(s, row(s, id, false), List.of());
        });
    }

    public ObjectNode update(Access access, String rowId, JsonNode patch, int version, int configVersion) {
        return tx.execute(status -> {
            Scope s = scope(access, true); Row row = row(s, rowId, false);
            if (!edits(s, row)) throw new ApiException(ErrorCode.FORBIDDEN, "你没有修改这条记录的权限");
            if (row.version() != version || s.config().path("version").asInt() != configVersion) throw conflict();
            if (patch == null || !patch.isObject()) throw invalid("修改内容无效");
            ObjectNode before = (ObjectNode) specs.read(row.json()), merged = before.deepCopy();
            patch.fields().forEachRemaining(e -> merged.set(e.getKey(), e.getValue()));
            ObjectNode normalized = FormSchema.values(merged, s.config(), mapper, true);
            db.update("update spec_form_rows set values_json=?,version=version+1,updated_at=? where id=?", normalized.toString(), System.currentTimeMillis(), rowId);
            event(s, rowId, "UPDATE", before, normalized);
            return rowView(s, row(s, rowId, false), items.findByProjectIdOrderByCreatedAtAsc(s.project().getId()));
        });
    }

    public void deleteOrRestore(Access access, String rowId, int version, boolean restore) {
        tx.executeWithoutResult(status -> {
            Scope s = scope(access, true); Row row = row(s, rowId, restore);
            if (restore) requireOwner(s);
            else if (!edits(s, row) || (!s.actor().owner() && !row.creator().equals(s.actor().key()))) throw new ApiException(ErrorCode.FORBIDDEN);
            if (row.version() != version) throw conflict();
            if (restore && !"owner".equals(row.creator()) && !s.config().path("allowMultiple").asBoolean()
                    && db.queryForObject("select count(*) from spec_form_rows where project_id=? and creator_key=? and deleted=0", Long.class, s.project().getId(), row.creator()) > 0)
                throw invalid("此人已有有效记录，需先处理现有记录再恢复");
            db.update("update spec_form_rows set deleted=?,version=version+1,updated_at=? where id=?", restore ? 0 : 1, System.currentTimeMillis(), rowId);
            event(s, rowId, restore ? "RESTORE" : "DELETE", specs.read(row.json()), null);
        });
    }

    public ObjectNode discussion(Access access, String rowId) {
        Scope s = scope(access, false); Row r = row(s, rowId, false);
        ObjectNode out = mapper.createObjectNode();
        out.set("comments", mapper.valueToTree(db.query("select id,actor_name,content,created_at from spec_form_comments where project_id=? and row_id=? order by created_at,id limit 200",
                (rs, n) -> Map.of("id", rs.getString(1), "name", rs.getString(2), "content", rs.getString(3), "createdAt", rs.getLong(4)), s.project().getId(), r.id())));
        out.set("events", mapper.valueToTree(db.query("select actor_name,action,before_json,after_json,created_at from spec_form_events where project_id=? and row_id=? order by created_at desc,id limit 50",
                (rs, n) -> {
                    ObjectNode event = mapper.createObjectNode().put("name", rs.getString(1)).put("action", rs.getString(2)).put("createdAt", rs.getLong(5));
                    if (rs.getString(3) != null) event.set("before", specs.read(rs.getString(3)));
                    if (rs.getString(4) != null) event.set("after", specs.read(rs.getString(4)));
                    return event;
                }, s.project().getId(), r.id())));
        return out;
    }

    public void comment(Access access, String rowId, String content) {
        String text = FormSchema.text(content, 1000, "留言", true);
        tx.executeWithoutResult(status -> {
            Scope s = scope(access, true); Row r = row(s, rowId, false);
            if (!s.config().path("allowComments").asBoolean() || (!s.actor().owner() && (!s.config().path("accepting").asBoolean() || "VIEWER".equals(s.actor().role()))))
                throw new ApiException(ErrorCode.FORBIDDEN, "留言已关闭，或你只有查看权限");
            if (db.queryForObject("select count(*) from spec_form_comments where row_id=?", Long.class, r.id()) >= 200) throw new ApiException(ErrorCode.RATE_LIMITED, "该记录最多保留 200 条留言");
            db.update("insert into spec_form_comments(id,project_id,row_id,actor_name,content,created_at) values(?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), s.project().getId(), r.id(), s.actor().name(), text, System.currentTimeMillis());
        });
    }

    public String authorizeUpload(Access access, String rowId) {
        Scope s = scope(access, false); Row row = row(s, rowId, false);
        if (!edits(s, row) && !(creates(s) && row.creator().equals(s.actor().key()))) throw new ApiException(ErrorCode.FORBIDDEN, "没有添加附件的权限");
        return row.name();
    }
    public MediaFile attachment(Access access, String rowId, String itemId) {
        Scope s = scope(access, false); row(s, rowId, false);
        var item = items.findByIdAndProjectId(itemId, s.project().getId()).filter(i -> rowId.equals(i.getFormRowId()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return storage.require(s.project().getOwnerId(), item.getFileId());
    }

    void attached(Access access, String rowId, String filename) {
        Scope s = scope(access, false); row(s, rowId, false);
        db.update("update spec_form_rows set updated_at=?,version=version+1 where id=?", System.currentTimeMillis(), rowId);
        event(s, rowId, "ATTACH", null, mapper.createObjectNode().put("filename", filename));
    }

    public void uploadOwner(Access access, String rowId, String slotKey, org.springframework.web.multipart.MultipartFile file) {
        Scope initial = scope(access, false); requireOwner(initial); authorizeUpload(access, rowId);
        if (file.isEmpty() || file.getSize() > CollectionService.MAX_FILE_BYTES) throw invalid("附件为空或超过 200MB");
        MediaFile uploaded = uploads.upload(initial.project().getOwnerId(), file,
                Set.of(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE, MediaKind.PDF, MediaKind.DOCUMENT));
        try {
            tx.executeWithoutResult(status -> {
                Scope s = scope(access, true); requireOwner(s); Row row = row(s, rowId, false);
                JsonNode slot = SpecSchema.slotsByKey(specs.spec(s.project())).get(slotKey);
                if (slot == null || !SpecMatcher.compatible(slot.path("kind").asText(), uploaded.kind())) throw invalid("文件类型与材料项不符");
                var item = specs.attach(s.project().getOwnerId(), s.project().getId(), List.of(new SpecService.Attach(uploaded.getId(), uploaded.getOriginalName()))).get(0);
                item.setFormRowId(rowId); item.setSubmittedBy(row.name()); item.setSlotKey(slotKey);
                item.setPersonKey(CollectionService.resolvePerson(specs.roster(s.project()), row.name(), ""));
                item.setMatchMethod("SUBMISSION"); item.setMatchNote("负责人为收集表记录添加附件");
                specs.applyStatus(item, specs.spec(s.project()), uploaded, true); items.save(item);
                attached(access, rowId, uploaded.getOriginalName());
            });
        } catch (RuntimeException e) { storage.deleteFile(uploaded); throw e; }
    }

    public ObjectNode statistics(Access access) {
        Scope s = scope(access, false); List<Row> rows = visibleRows(s, false);
        ObjectNode out = mapper.createObjectNode().put("rows", rows.size()).put("participants", rows.stream().map(Row::creator).distinct().count());
        Set<String> rowIds = new HashSet<>(); rows.forEach(r -> rowIds.add(r.id()));
        out.put("attachments", items.findByProjectIdOrderByCreatedAtAsc(s.project().getId()).stream().filter(i -> rowIds.contains(i.getFormRowId())).count());
        out.put("scope", seesAll(s) ? "ALL" : "OWN");
        var fields = out.putArray("fields");
        List<JsonNode> values = rows.stream().map(r -> specs.read(r.json())).toList();
        for (JsonNode f : s.config().path("fields")) {
            String key = f.path("key").asText();
            ObjectNode n = fields.addObject().put("key", key).put("label", f.path("label").asText()).put("type", f.path("type").asText());
            TreeMap<String, Integer> groups = new TreeMap<>();
            List<BigDecimal> numbers = new ArrayList<>(); int filled = 0;
            for (JsonNode v : values) {
                JsonNode x = v.get(key);
                if (x == null || x.isNull() || x.asText().isBlank()) continue;
                filled++; String val = x.asText(); groups.merge(val, 1, Integer::sum);
                if ("NUMBER".equals(f.path("type").asText())) numbers.add(new BigDecimal(val));
            }
            n.put("filled", filled).put("missing", rows.size() - filled).put("distinct", groups.size())
                    .put("duplicates", groups.values().stream().mapToInt(c -> Math.max(0, c - 1)).sum());
            var g = n.putArray("groups");
            groups.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                    .limit(100).forEach(e -> g.addObject().put("value", e.getKey()).put("count", e.getValue()));
            n.put("groupsTruncated", groups.size() > 100);
            if (!numbers.isEmpty()) {
                BigDecimal sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                n.put("sum", sum.stripTrailingZeros().toPlainString()).put("average", sum.divide(BigDecimal.valueOf(numbers.size()), 6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
                        .put("min", Collections.min(numbers).toPlainString()).put("max", Collections.max(numbers).toPlainString());
            }
        }
        return out;
    }

    public byte[] export(Access access) {
        Scope s = scope(access, false); List<Row> rows = visibleRows(s, false);
        try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var out = new java.io.ByteArrayOutputStream()) {
            var sheet = book.createSheet("收集汇总"); var header = sheet.createRow(0);
            List<String> cols = new ArrayList<>(List.of("提交人", "提交时间", "更新时间"));
            s.config().path("fields").forEach(f -> cols.add(f.path("label").asText()));
            for (int i = 0; i < cols.size(); i++) header.createCell(i, org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(cols.get(i));
            for (int index = 0; index < rows.size(); index++) {
                Row r = rows.get(index); var row = sheet.createRow(index + 1);
                List<String> cells = new ArrayList<>(List.of(r.name(), java.time.Instant.ofEpochMilli(r.createdAt()).toString(), java.time.Instant.ofEpochMilli(r.updatedAt()).toString()));
                JsonNode values = specs.read(r.json()); s.config().path("fields").forEach(f -> cells.add(values.path(f.path("key").asText()).asText("")));
                // Explicit strings preserve decimal precision and cannot turn formula-looking submissions into formulas.
                for (int i = 0; i < cells.size(); i++) row.createCell(i, org.apache.poi.ss.usermodel.CellType.STRING).setCellValue(cells.get(i));
            }
            sheet.createFreezePane(1, 1); sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, rows.size()), 0, cols.size() - 1));
            for (int i = 0; i < cols.size(); i++) sheet.setColumnWidth(i, 22 * 256);
            book.write(out); return out.toByteArray();
        } catch (java.io.IOException e) { throw new ApiException(ErrorCode.INTERNAL_ERROR, "汇总表生成失败"); }
    }

    private void event(Scope s, String rowId, String action, JsonNode before, JsonNode after) {
        db.update("update spec_projects set updated_at=? where id=?", System.currentTimeMillis(), s.project().getId());
        db.update("insert into spec_form_events(id,project_id,row_id,actor_name,action,before_json,after_json,created_at) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), s.project().getId(), rowId, s.actor().name(), action,
                before == null ? null : before.toString(), after == null ? null : after.toString(), System.currentTimeMillis());
    }
    private static boolean validSecret(String s) { return s != null && s.matches("[A-Za-z0-9_-]{43}"); }
    private static ApiException missingLink() { return new ApiException(ErrorCode.NOT_FOUND, "收集链接不存在、已到期或已停止收集"); }
    private static ApiException conflict() { return new ApiException(ErrorCode.VERSION_CONFLICT, "记录或表格设置已被修改，请刷新核对后再保存；你的草稿尚未覆盖服务器内容"); }
}
