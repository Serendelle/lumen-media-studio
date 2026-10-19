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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * spec.deliver — builds the hand-in package from an immutable snapshot of the project taken when the task starts:
 *
 * <pre>
 * 00_交付说明.txt
 * 01_汇总表/汇总.xlsx                       organiser's XLSX template filled row by row (or a generated table)
 * 02_个人表/0001_张三.docx                   one filled DOCX per person (when a DOCX template is set)
 * 03_材料/0001_张三/{命名规则}.ext            the delivered file of each material (verified fix or compliant upload)
 * 03_材料/0001_张三/原始素材/...              originals of fixed files (optional)
 * 90_校验报告/manifest.csv · exceptions.csv · verification.json
 * sha256sums.txt
 * </pre>
 *
 * Every generated file is re-opened and checked (no placeholder left, row count, checksums) before the ZIP is
 * made; problems are listed in exceptions.csv instead of being dropped. Params: {projectId, onlyComplete, includeOriginals}.
 */
@Component
public class SpecDeliveryHandler implements MediaTaskHandler {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SpecProjectRepository projects;
    private final SpecService specs;
    private final StorageService storage;
    private final ObjectMapper mapper;

    public SpecDeliveryHandler(SpecProjectRepository projects, SpecService specs, StorageService storage, ObjectMapper mapper) {
        this.projects = projects;
        this.specs = specs;
        this.storage = storage;
        this.mapper = mapper;
    }

    @Override public String type() { return "spec.deliver"; }

    @Override
    public void validate(JsonNode p, List<MediaFile> inputs) {
        if (!p.path("projectId").isTextual()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少 projectId");
    }

    private record Person(int serial, String key, String name, JsonNode values, Map<String, SpecItem> delivered,
                          List<String> problems, boolean complete) {}

    @Override
    public HandlerResult execute(TaskContext ctx) throws Exception {
        // ---- SNAPSHOT (5%) ----
        ctx.progress(1, "snapshot");
        SpecProject project = projects.findByIdAndOwnerId(ctx.params().path("projectId").asText(), ctx.ownerId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "项目不存在"));
        boolean onlyComplete = ctx.params().path("onlyComplete").asBoolean(false);
        boolean originals = ctx.params().path("includeOriginals").asBoolean(false);
        JsonNode spec = specs.spec(project), roster = specs.roster(project);
        if (roster == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请先导入名单");
        List<JsonNode> slots = new ArrayList<>();
        spec.path("slots").forEach(slots::add);
        List<SpecItem> items = specs.items(ctx.ownerId(), project.getId());
        String nameField = roster.path("nameField").asText();

        Map<String, List<SpecItem>> cell = new HashMap<>();
        for (SpecItem it : items) {
            if (it.getPersonKey() != null && it.getSlotKey() != null) cell.computeIfAbsent(it.getPersonKey() + "|" + it.getSlotKey(), k -> new ArrayList<>()).add(it);
        }
        ArrayNode exceptions = mapper.createArrayNode();
        List<Person> people = new ArrayList<>();
        int serial = 0;
        for (JsonNode p : roster.path("people")) {
            serial++;
            String key = p.path("key").asText();
            Map<String, SpecItem> delivered = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            for (JsonNode s : slots) {
                String sk = s.path("key").asText();
                List<SpecItem> c = cell.getOrDefault(key + "|" + sk, List.of());
                SpecItem best = SpecService.best(c);
                if (best != null && SpecService.usable(best.getStatus())) {
                    delivered.put(sk, best);
                    if (c.size() > 1) exception(exceptions, serial, p.path("values").path(nameField).asText(), "DUPLICATE",
                            s.path("label").asText(), "同一材料有 " + c.size() + " 个文件，已采用「" + best.getRelativePath() + "」", "确认采用的文件是否正确");
                } else if (s.path("required").asBoolean(true)) {
                    String why = best == null ? "未提交" : statusText(best);
                    problems.add(s.path("label").asText() + "：" + why);
                    exception(exceptions, serial, p.path("values").path(nameField).asText(), best == null ? "MISSING" : best.getStatus(),
                            s.path("label").asText(), why, best == null ? "联系本人补交" : "在材料页查看原因并重新提交或修正");
                }
            }
            people.add(new Person(serial, key, p.path("values").path(nameField).asText(), p.path("values"), delivered, problems, problems.isEmpty()));
        }
        for (SpecItem it : items) {
            if (it.getPersonKey() == null || it.getSlotKey() == null) {
                exception(exceptions, 0, "", "UNMATCHED", "", "文件「" + it.getRelativePath() + "」未匹配到人员或材料项，未放入交付包", "在材料页手动指定");
            }
        }
        List<Person> included = onlyComplete ? people.stream().filter(Person::complete).toList() : people;
        if (included.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, onlyComplete ? "没有材料齐全的人员，未生成交付包" : "名单为空，未生成交付包",
                    Map.of("exceptions", mapper.convertValue(exceptions, Object.class)));
        }

        Path root = Files.createDirectories(ctx.workDir().resolve("pkg"));
        ArrayNode manifest = mapper.createArrayNode();
        ctx.progress(5, "snapshot");

        // ---- XLSX (20%) ----
        ctx.progress(6, "fill-xlsx");
        List<String> known = new ArrayList<>();
        known.add("序号");
        roster.path("fields").forEach(f -> known.add(f.asText()));
        slots.forEach(s -> known.add(s.path("label").asText()));
        known.add("状态");
        List<Map<String, String>> rows = new ArrayList<>();
        for (Person p : included) rows.add(values(p, slots, project));
        Path sumDir = Files.createDirectories(root.resolve("01_汇总表"));
        Path xlsx = sumDir.resolve("汇总.xlsx");
        int headerRow0, firstCol;
        if (project.getXlsxTemplateFileId() != null) {
            MediaFile t = storage.require(ctx.ownerId(), project.getXlsxTemplateFileId());
            TemplateFiller.SheetPlan plan = TemplateFiller.planXlsx(storage.pathOf(t), project.getXlsxHeaderRow(), known);
            TemplateFiller.fillXlsx(storage.pathOf(t), xlsx, plan, rows);
            headerRow0 = plan.headerRow();
            firstCol = plan.columns().keySet().iterator().next();
        } else {
            List<String> cols = new ArrayList<>(known);
            TemplateFiller.newXlsx(xlsx, cols, rows);
            headerRow0 = 0;
            firstCol = 0;
        }
        ctx.progress(25, "fill-xlsx");

        // ---- DOCX (25%) ----
        Map<Integer, Path> docs = new LinkedHashMap<>();
        Map<Integer, Set<String>> unresolved = new HashMap<>();
        if (project.getDocxTemplateFileId() != null) {
            MediaFile t = storage.require(ctx.ownerId(), project.getDocxTemplateFileId());
            Path dir = Files.createDirectories(root.resolve("02_个人表"));
            int done = 0;
            for (Person p : included) {
                if (ctx.token().isCancelled()) throw new ApiException(ErrorCode.CANCELLED);
                Path out = dir.resolve(String.format("%04d", p.serial()) + "_" + safe(p.name(), 40) + ".docx");
                Set<String> missing = TemplateFiller.fillDocx(storage.pathOf(t), out, values(p, slots, project));
                if (!missing.isEmpty()) {
                    exception(exceptions, p.serial(), p.name(), "TEMPLATE_BINDING_FAILED", "个人表",
                            "模板中的占位符没有对应数据：" + String.join("、", missing), "检查占位符是否与名单列名一致");
                }
                docs.put(p.serial(), out);
                unresolved.put(p.serial(), missing);
                ctx.progress(25 + (int) (25.0 * ++done / included.size()), "fill-docx");
            }
        }
        ctx.progress(50, "fill-docx");

        // ---- MATERIALS (25%) ----
        String pattern = spec.path("naming").path("pattern").asText("{序号}_{姓名}_{材料}");
        Path matDir = Files.createDirectories(root.resolve("03_材料"));
        int done = 0;
        for (Person p : included) {
            if (ctx.token().isCancelled()) throw new ApiException(ErrorCode.CANCELLED);
            Path dir = Files.createDirectories(matDir.resolve(String.format("%04d", p.serial()) + "_" + safe(p.name(), 40)));
            Set<String> used = new HashSet<>();
            for (JsonNode s : slots) {
                SpecItem it = p.delivered().get(s.path("key").asText());
                if (it == null) continue;
                String fileId = it.getFixedFileId() != null ? it.getFixedFileId() : it.getFileId();
                MediaFile f = storage.require(ctx.ownerId(), fileId);
                MediaFile original = storage.require(ctx.ownerId(), it.getFileId());
                String ext = StorageService.extensionOf(f.getStoredName());
                String base = applyPattern(pattern, p, s.path("label").asText(), StorageService.baseName(original.getOriginalName()));
                String name = unique(used, base, ext);
                Path target = dir.resolve(name);
                Files.copy(storage.pathOf(f), target);
                manifestRow(manifest, p, s.path("label").asText(), root.relativize(target), target, it.getFixedFileId() != null ? "FIXED" : "OK", fileId);
                if (originals && it.getFixedFileId() != null) {
                    Path od = Files.createDirectories(dir.resolve("原始素材"));
                    Path ot = od.resolve(unique(used, StorageService.baseName(StorageService.sanitizeName(original.getOriginalName())),
                            StorageService.extensionOf(original.getStoredName())));
                    Files.copy(storage.pathOf(original), ot);
                    manifestRow(manifest, p, s.path("label").asText() + "（原始）", root.relativize(ot), ot, "ORIGINAL", original.getId());
                }
            }
            ctx.progress(50 + (int) (25.0 * ++done / included.size()), "materials");
        }
        for (var e : docs.entrySet()) {
            Person p = included.stream().filter(x -> x.serial() == e.getKey()).findFirst().orElseThrow();
            manifestRow(manifest, p, "个人表", root.relativize(e.getValue()), e.getValue(), "GENERATED", project.getDocxTemplateFileId());
        }
        manifestRow(manifest, null, "汇总表", root.relativize(xlsx), xlsx, "GENERATED", project.getXlsxTemplateFileId());

        // ---- VERIFY (10%) : read everything back, independently of what was just written ----
        ctx.progress(76, "verifying");
        ArrayNode checks = mapper.createArrayNode();
        int rowsBack = TemplateFiller.countRows(xlsx, headerRow0, firstCol, included.size());
        check(checks, "汇总表行数", rowsBack == included.size(), included.size() + " 行", rowsBack + " 行");
        // placeholders without data were already reported per person; anything else still present is a fill failure
        int docsLeft = 0;
        for (var e : docs.entrySet()) {
            Set<String> left = new HashSet<>(TemplateFiller.remainingPlaceholders(e.getValue()));
            left.removeAll(unresolved.getOrDefault(e.getKey(), Set.of()));
            if (!left.isEmpty()) docsLeft++;
        }
        if (!docs.isEmpty()) check(checks, "个人表占位符", docsLeft == 0, "有数据的占位符全部替换",
                docsLeft == 0 ? "全部替换" + (unresolved.values().stream().anyMatch(x -> !x.isEmpty()) ? "（无数据的占位符见异常清单）" : "")
                        : docsLeft + " 份仍有未替换的占位符");
        int badSha = 0;
        for (JsonNode m : manifest) {
            if (!sha256(root.resolve(m.path("relativePath").asText())).equals(m.path("sha256").asText())) badSha++;
        }
        check(checks, "文件校验和", badSha == 0, manifest.size() + " 个文件一致", badSha == 0 ? manifest.size() + " 个文件一致" : badSha + " 个不一致");

        // ---- reports ----
        Path rep = Files.createDirectories(root.resolve("90_校验报告"));
        writeCsv(rep.resolve("manifest.csv"), List.of("序号", "姓名", "材料", "relativePath", "sha256", "sizeBytes", "validationStatus", "sourceFileId"),
                manifest, List.of("serial", "name", "material", "relativePath", "sha256", "sizeBytes", "status", "sourceFileId"));
        writeCsv(rep.resolve("exceptions.csv"), List.of("序号", "姓名", "错误码", "材料", "原因", "处理建议"),
                exceptions, List.of("serial", "name", "code", "material", "reason", "suggestion"));
        boolean passed = true;
        for (JsonNode c : checks) passed &= c.path("ok").asBoolean();
        long completeCount = people.stream().filter(Person::complete).count();
        ObjectNode verification = mapper.createObjectNode();
        verification.put("projectId", project.getId());
        verification.put("projectName", project.getName());
        verification.put("specVersion", project.getVersion());
        verification.put("generatedAt", java.time.Instant.now().toString());
        verification.put("traceId", ctx.taskId());
        verification.put("people", people.size());
        verification.put("included", included.size());
        verification.put("complete", completeCount);
        verification.put("exceptions", exceptions.size());
        verification.put("onlyComplete", onlyComplete);
        verification.set("checks", checks);
        verification.set("spec", spec);
        verification.put("software", "Lumen 材料规范官 · Apache POI");
        Files.writeString(rep.resolve("verification.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(verification), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("00_交付说明.txt"), readme(project, people.size(), included.size(), completeCount, exceptions.size(), onlyComplete, pattern), StandardCharsets.UTF_8);
        StringBuilder sums = new StringBuilder();
        try (var walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                sums.append(sha256(f)).append("  ").append(root.relativize(f).toString().replace('\\', '/')).append('\n');
            }
        }
        Files.writeString(root.resolve("sha256sums.txt"), sums.toString(), StandardCharsets.UTF_8);
        if (!passed) {
            throw new ApiException(ErrorCode.OUTPUT_INVALID, "交付包未通过回读核验，未生成正式包", Map.of("checks", mapper.convertValue(checks, Object.class)));
        }

        // ---- PACKAGE (15%) ----
        ctx.progress(88, "packaging");
        String zipName = "LUMEN_" + safe(project.getName(), 40) + "_" + LocalDateTime.now().format(STAMP) + "_" + ctx.taskId().substring(0, 8) + ".zip";
        Path zip = ctx.workDir().resolve("out.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip), StandardCharsets.UTF_8); var walk = Files.walk(root)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                z.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
        ctx.progress(97, "packaging");
        MediaFile out = storage.registerOutput(ctx.ownerId(), zip, zipName, MediaKind.ARCHIVE, "application/zip", ctx.taskId());
        storage.keepUntil(out, System.currentTimeMillis() + SpecService.PROJECT_RETENTION.toMillis());

        ObjectNode summary = mapper.createObjectNode();
        summary.put("projectId", project.getId());
        summary.put("overall", exceptions.isEmpty() ? "SUCCEEDED" : "PARTIAL_SUCCESS");
        summary.put("people", people.size());
        summary.put("included", included.size());
        summary.put("complete", completeCount);
        summary.put("files", manifest.size());
        summary.put("exceptionCount", exceptions.size());
        ArrayNode ex = summary.putArray("exceptions");
        for (int i = 0; i < Math.min(50, exceptions.size()); i++) ex.add(exceptions.get(i));
        summary.set("checks", checks);
        summary.put("outputSize", Files.exists(storage.pathOf(out)) ? Files.size(storage.pathOf(out)) : 0);
        return new HandlerResult(out, summary);
    }

    // ---------------- helpers ----------------

    private Map<String, String> values(Person p, List<JsonNode> slots, SpecProject project) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("项目名称", project.getName());
        // slot labels first so a roster column with the same name wins
        for (JsonNode s : slots) {
            SpecItem it = p.delivered().get(s.path("key").asText());
            v.put(s.path("label").asText(), it == null ? (s.path("required").asBoolean(true) ? "缺失" : "未提交") : (it.getFixedFileId() != null ? "已提交（已规范化）" : "已提交"));
        }
        p.values().fields().forEachRemaining(e -> v.put(e.getKey(), e.getValue().asText()));
        v.put("序号", String.valueOf(p.serial()));
        v.put("状态", p.problems().isEmpty() ? "材料齐全" : "缺：" + String.join("；", p.problems()));
        return v;
    }

    static String applyPattern(String pattern, Person p, String material, String originalBase) {
        java.util.regex.Matcher m = SpecSchema.TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tok = m.group(1);
            String val = switch (tok) {
                case "序号" -> String.format("%04d", p.serial());
                case "材料" -> material;
                case "原文件名" -> originalBase;
                default -> p.values().path(tok).asText("");
            };
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        String s = safe(sb.toString(), 80);
        return s.isBlank() ? String.format("%04d", p.serial()) : s;
    }

    /** File-system safe: no separators, no reserved characters, no leading dots, bounded length. */
    static String safe(String s, int max) {
        String t = (s == null ? "" : s).replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^\\.+", "");
        if (t.length() > max) t = t.substring(0, max);
        return t.isBlank() ? "_" : t;
    }

    private static String unique(Set<String> used, String base, String ext) {
        String name = base + (ext.isEmpty() ? "" : "." + ext);
        for (int n = 2; !used.add(name.toLowerCase(Locale.ROOT)); n++) name = base + "(" + n + ")" + (ext.isEmpty() ? "" : "." + ext);
        return name;
    }

    private void manifestRow(ArrayNode manifest, Person p, String material, Path rel, Path file, String status, String sourceFileId) throws IOException {
        ObjectNode m = manifest.addObject();
        m.put("serial", p == null ? "" : String.format("%04d", p.serial()));
        m.put("name", p == null ? "" : p.name());
        m.put("material", material);
        m.put("relativePath", rel.toString().replace('\\', '/'));
        m.put("sha256", sha256(file));
        m.put("sizeBytes", Files.size(file));
        m.put("status", status);
        m.put("sourceFileId", sourceFileId == null ? "" : sourceFileId);
    }

    private static void exception(ArrayNode ex, int serial, String name, String code, String material, String reason, String suggestion) {
        ObjectNode e = ex.addObject();
        e.put("serial", serial == 0 ? "" : String.format("%04d", serial));
        e.put("name", name);
        e.put("code", code);
        e.put("material", material);
        e.put("reason", reason);
        e.put("suggestion", suggestion);
    }

    private static void check(ArrayNode checks, String name, boolean ok, String expected, String actual) {
        ObjectNode c = checks.addObject();
        c.put("name", name);
        c.put("ok", ok);
        c.put("expected", expected);
        c.put("actual", actual);
    }

    private static String statusText(SpecItem it) {
        return switch (it.getStatus()) {
            case "FIXABLE" -> "不符合要求（可自动修正，尚未修正）";
            case "FIXING" -> "正在修正，未完成";
            case "FIX_FAILED" -> "修正失败";
            case "INVALID" -> "不符合要求且无法自动修正";
            default -> "未匹配";
        };
    }

    /** UTF-8 with BOM so Excel opens Chinese correctly; cells that could start a formula are prefixed with '. */
    static void writeCsv(Path out, List<String> header, ArrayNode rows, List<String> keys) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", header.stream().map(Csv::cell).toList())).append("\r\n");
        for (JsonNode r : rows) {
            List<String> cells = new ArrayList<>();
            for (String k : keys) cells.add(Csv.cell(r.path(k).asText()));
            sb.append(String.join(",", cells)).append("\r\n");
        }
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    static final class Csv {
        static String cell(String v) {
            String s = v == null ? "" : v;
            if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
            if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) s = "\"" + s.replace("\"", "\"\"") + "\"";
            return s;
        }
    }

    private static String readme(SpecProject p, int people, int included, long complete, int exceptions, boolean onlyComplete, String pattern) {
        return "交付包：" + p.getName() + "\r\n"
                + "生成时间：" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "\r\n\r\n"
                + "名单 " + people + " 人，本包包含 " + included + " 人" + (onlyComplete ? "（仅材料齐全者）" : "") + "，其中材料齐全 " + complete + " 人。\r\n"
                + "异常 " + exceptions + " 条，详见 90_校验报告/exceptions.csv。\r\n\r\n"
                + "目录说明：\r\n"
                + "  01_汇总表   按名单逐行填写的汇总表\r\n"
                + "  02_个人表   每人一份按模板填写的个人表（设置了个人表模板时）\r\n"
                + "  03_材料     每人一个文件夹，文件按「" + pattern + "」命名；标注为已规范化的文件已按规范自动压缩或转换并重新检测\r\n"
                + "  90_校验报告 manifest.csv（每个文件的来源与 SHA-256）、exceptions.csv（缺失与不合格项）、verification.json（核验明细）\r\n"
                + "  sha256sums.txt 全部文件的校验和，可用 sha256sum -c 核对\r\n";
    }

    static String sha256Bytes(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
