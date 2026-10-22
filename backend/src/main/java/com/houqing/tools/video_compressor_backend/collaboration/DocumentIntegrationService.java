package com.houqing.tools.video_compressor_backend.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Project-scoped metadata for external documents and their manually imported, immutable exports. */
@Service
public class DocumentIntegrationService {
    private static final Set<String> DOCUMENT_TYPES = Set.of("DOCUMENT", "SPREADSHEET", "SLIDE", "OTHER");
    private static final Set<String> CREATE_FIELDS = Set.of("title", "externalUrl", "documentType", "visibility", "visibleUserIds", "provider");
    private static final Set<String> UPDATE_FIELDS = Set.of("title", "externalUrl", "documentType", "visibility", "visibleUserIds", "version");
    private final ProjectAccessService access;
    private final ProjectFileService files;
    private final JdbcTemplate jdbc;
    private final Map<String, DocumentProvider> providers;

    public DocumentIntegrationService(ProjectAccessService access, ProjectFileService files, JdbcTemplate jdbc,
                                      List<DocumentProvider> providers) {
        this.access = access;
        this.files = files;
        this.jdbc = jdbc;
        Map<String, DocumentProvider> all = new LinkedHashMap<>();
        for (DocumentProvider provider : providers) all.put(provider.id(), provider);
        this.providers = Map.copyOf(all);
    }

    public Map<String, Object> list(long uid, String projectId, int page, int size) {
        ProjectAccessService.Access actor = access.require(uid, projectId);
        int p = Math.max(0, page), s = Math.min(100, Math.max(1, size));
        String scope = "d.project_id=? AND d.unlinked=0 AND (?=1 OR d.visibility='ALL' OR EXISTS "
                + "(SELECT 1 FROM project_document_members dm WHERE dm.document_id=d.id AND dm.user_id=?))";
        int owner = actor.ownerId() == uid ? 1 : 0;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM project_documents d WHERE " + scope, Long.class, projectId, owner, uid);
        List<Map<String, Object>> result = jdbc.query("SELECT d.*,u.username,u.display_name FROM project_documents d "
                        + "JOIN users u ON u.id=d.created_by WHERE " + scope + " ORDER BY d.created_at DESC,d.id LIMIT ? OFFSET ?",
                (rs, row) -> view(rs), projectId, owner, uid, s, (long) p * s);
        for (Map<String, Object> document : result) document.put("visibleUserIds", visibleMembers(document.get("id").toString()));
        return Map.of("items", result, "page", p, "size", s, "total", count == null ? 0L : count);
    }

    @Transactional
    public Map<String, Object> create(long uid, String projectId, JsonNode body) {
        ProjectAccessService.Access actor = access.lock(uid, projectId);
        fields(body, CREATE_FIELDS);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM project_documents WHERE project_id=? AND unlinked=0", Long.class, projectId);
        if (count != null && count >= 200) throw new ApiException(ErrorCode.VALIDATION_FAILED, "每个项目最多关联 200 份文档");
        String providerId = text(body, "provider", "TENCENT_DOCS_LINK");
        DocumentProvider provider = provider(providerId);
        DocumentProvider.Link link = provider.validate(text(body, "externalUrl", null));
        String title = title(body, null);
        String type = documentType(body, link.documentType());
        String visibility = visibility(body, "ALL");
        List<Long> members = members(body, projectId, visibility, List.of());
        if (actor.ownerId() != uid && (!"ALL".equals(visibility) || !members.isEmpty())) throw ownerVisibility();
        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO project_documents (id,project_id,provider,title,document_type,external_url,visibility,version,created_by,created_at,updated_at,unlinked) VALUES (?,?,?,?,?,?,?,1,?,?,?,0)",
                id, projectId, provider.id(), title, type, link.url(), visibility, uid, now, now);
        replaceMembers(id, members);
        changed(projectId);
        access.activity(actor, "DOCUMENT_LINKED", id);
        return document(projectId, id);
    }

    @Transactional
    public Map<String, Object> update(long uid, String projectId, String id, JsonNode body) {
        ProjectAccessService.Access actor = access.lock(uid, projectId);
        access.requireDocument(uid, projectId, id);
        fields(body, UPDATE_FIELDS);
        Map<String, Object> old = document(projectId, id);
        if (!body.has("version") || !body.get("version").isIntegralNumber() || !body.get("version").canConvertToInt()
                || body.get("version").asInt() != number(old.get("version"))) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }
        DocumentProvider provider = provider(old.get("provider").toString());
        DocumentProvider.Link link = provider.validate(text(body, "externalUrl", old.get("externalUrl").toString()));
        String type = documentType(body, old.get("documentType").toString());
        String visibility = visibility(body, old.get("visibility").toString());
        List<Long> currentMembers = visibleMembers(id);
        List<Long> members = members(body, projectId, visibility, currentMembers);
        if (actor.ownerId() != uid && (!visibility.equals(old.get("visibility"))
                || !new LinkedHashSet<>(members).equals(new LinkedHashSet<>(currentMembers)))) throw ownerVisibility();
        int version = number(old.get("version"));
        int changed = jdbc.update("UPDATE project_documents SET title=?,document_type=?,external_url=?,visibility=?,version=version+1,updated_at=? WHERE id=? AND project_id=? AND unlinked=0 AND version=?",
                title(body, old.get("title").toString()), type, link.url(), visibility, System.currentTimeMillis(), id, projectId, version);
        if (changed != 1) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        replaceMembers(id, members);
        changed(projectId);
        access.activity(actor, "DOCUMENT_UPDATED", id);
        return document(projectId, id);
    }

    @Transactional
    public void unlink(long uid, String projectId, String id) {
        ProjectAccessService.Access actor = access.lock(uid, projectId);
        access.requireDocument(uid, projectId, id);
        int changed = jdbc.update("UPDATE project_documents SET unlinked=1,version=version+1,updated_at=? WHERE project_id=? AND id=? AND unlinked=0",
                System.currentTimeMillis(), projectId, id);
        if (changed != 1) throw new ApiException(ErrorCode.NOT_FOUND);
        // Physical files remain immutable; access to this unlinked document's versions is withdrawn.
        changed(projectId);
        access.activity(actor, "DOCUMENT_UNLINKED", id);
    }

    public Map<String, Object> imports(long uid, String projectId, String id, MultipartFile file) {
        access.write(uid, projectId);
        access.requireDocument(uid, projectId, id);
        Map<String, Object> document = document(projectId, id);
        DocumentProvider provider = provider(document.get("provider").toString());
        String filename = file == null ? null : file.getOriginalFilename();
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (file == null || file.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请选择从腾讯文档实际导出的文件");
        if (file.getSize() > 200L * 1024 * 1024) throw new ApiException(ErrorCode.FILE_TOO_LARGE, "文档导出文件不能超过 200 MB");
        if (!provider.importExtensions(document.get("documentType").toString()).contains(extension)) {
            throw new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT, "文件格式与关联文档的类型不匹配");
        }
        List<String> assets = jdbc.queryForList("SELECT fv.asset_id FROM project_document_versions dv JOIN project_file_versions fv ON fv.id=dv.file_version_id WHERE dv.document_id=? ORDER BY dv.version_number DESC LIMIT 1", String.class, id);
        // The file service revalidates current membership and document scope inside its short commit transaction.
        // It also records the document version and emits the actual FILE_UPLOADED activity atomically.
        return files.upload(uid, projectId, file, assets.isEmpty() ? null : assets.get(0), id);
    }

    public List<Map<String, Object>> versions(long uid, String projectId, String id) {
        access.require(uid, projectId);
        access.requireDocument(uid, projectId, id);
        // Active-document lookup deliberately fails after unlinking, including historical versions.
        document(projectId, id);
        List<Map<String, Object>> imported = jdbc.queryForList("SELECT dv.id,dv.file_version_id,dv.version_number,dv.imported_by,dv.imported_at,fv.asset_id "
                + "FROM project_document_versions dv JOIN project_file_versions fv ON fv.id=dv.file_version_id WHERE dv.document_id=? ORDER BY dv.version_number DESC", id);
        if (imported.isEmpty()) return List.of();
        Map<String, Map<String, Object>> actualVersions = new LinkedHashMap<>();
        Set<String> assets = new LinkedHashSet<>();
        for (Map<String, Object> row : imported) assets.add(row.get("asset_id").toString());
        for (String asset : assets) for (Map<String, Object> row : files.versions(uid, projectId, asset)) actualVersions.put(row.get("id").toString(), row);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : imported) {
            Map<String, Object> actual = actualVersions.get(row.get("file_version_id").toString());
            if (actual == null) continue;
            Map<String, Object> item = new LinkedHashMap<>(actual);
            item.put("documentVersionId", row.get("id"));
            item.put("documentVersionNumber", row.get("version_number"));
            item.put("importedBy", row.get("imported_by"));
            item.put("importedAt", row.get("imported_at"));
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> document(String projectId, String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT d.*,u.username,u.display_name FROM project_documents d JOIN users u ON u.id=d.created_by WHERE d.project_id=? AND d.id=? AND d.unlinked=0",
                (rs, row) -> view(rs), projectId, id);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND);
        Map<String, Object> result = rows.get(0);
        result.put("visibleUserIds", visibleMembers(id));
        return result;
    }

    private Map<String, Object> view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getString("id"));
        row.put("projectId", rs.getString("project_id"));
        row.put("provider", rs.getString("provider"));
        row.put("title", rs.getString("title"));
        row.put("documentType", rs.getString("document_type"));
        row.put("externalUrl", rs.getString("external_url"));
        row.put("visibility", rs.getString("visibility"));
        row.put("version", rs.getInt("version"));
        row.put("createdBy", rs.getLong("created_by"));
        String displayName = rs.getString("display_name");
        row.put("creatorName", displayName == null || displayName.isBlank() ? rs.getString("username") : displayName);
        row.put("createdAt", rs.getLong("created_at"));
        row.put("updatedAt", rs.getLong("updated_at"));
        row.put("syncMode", "MANUAL_IMPORT");
        return row;
    }

    private List<Long> visibleMembers(String id) {
        return jdbc.queryForList("SELECT user_id FROM project_document_members WHERE document_id=? ORDER BY user_id", Long.class, id);
    }

    private List<Long> members(JsonNode body, String projectId, String visibility, List<Long> fallback) {
        if ("ALL".equals(visibility)) {
            if (body.has("visibleUserIds") && (!body.get("visibleUserIds").isArray() || !body.get("visibleUserIds").isEmpty())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "所有成员可见时无需指定成员");
            }
            return List.of();
        }
        if (!body.has("visibleUserIds")) return fallback;
        JsonNode values = body.get("visibleUserIds");
        if (!values.isArray() || values.size() > 100) throw new ApiException(ErrorCode.VALIDATION_FAILED, "指定成员数量不能超过 100");
        Set<Long> existing = Set.copyOf(access.memberIds(projectId));
        Set<Long> selected = new LinkedHashSet<>();
        for (JsonNode node : values) {
            if (!node.isIntegralNumber() || !node.canConvertToLong() || !existing.contains(node.asLong())) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "文档只能分享给本项目的现有成员");
            }
            selected.add(node.asLong());
        }
        return List.copyOf(selected);
    }

    private void replaceMembers(String id, List<Long> members) {
        jdbc.update("DELETE FROM project_document_members WHERE document_id=?", id);
        for (long uid : members) jdbc.update("INSERT INTO project_document_members (document_id,user_id) VALUES (?,?)", id, uid);
    }

    private void changed(String projectId) {
        jdbc.update("UPDATE spec_projects SET publication_status='DRAFT',team_version=team_version+1 WHERE id=?", projectId);
    }

    private String title(JsonNode body, String fallback) {
        String value = text(body, "title", fallback);
        if (value == null || value.trim().isEmpty() || value.trim().length() > 120) throw new ApiException(ErrorCode.VALIDATION_FAILED, "文档标题需为 1-120 个字");
        return value.trim();
    }

    private String documentType(JsonNode body, String fallback) {
        String value = text(body, "documentType", fallback);
        if (!DOCUMENT_TYPES.contains(value)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "文档类型不正确");
        return value;
    }

    private String visibility(JsonNode body, String fallback) {
        String value = text(body, "visibility", fallback);
        if (!Set.of("ALL", "SELECTED").contains(value)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "文档可见范围不正确");
        return value;
    }

    private DocumentProvider provider(String name) {
        DocumentProvider provider = providers.get(name);
        if (provider == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "暂不支持此文档服务商");
        return provider;
    }

    private String text(JsonNode body, String field, String fallback) {
        if (!body.has(field)) return fallback;
        if (!body.get(field).isTextual()) throw new ApiException(ErrorCode.VALIDATION_FAILED, field + " 必须是文本");
        return body.get(field).asText();
    }

    private void fields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请提交文档信息");
        body.fieldNames().forEachRemaining(key -> {
            if (!fields.contains(key)) throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的文档字段：" + key);
        });
    }

    private int number(Object value) { return ((Number) value).intValue(); }
    private ApiException ownerVisibility() { return new ApiException(ErrorCode.FORBIDDEN, "只有负责人可以设置文档可见范围"); }
}
