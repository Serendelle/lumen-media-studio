package com.houqing.tools.video_compressor_backend.ai.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the versioned system template (ai/prompts/system.yaml), the version registry and one SKILL.md per agent.
 * A skill's front matter declares its tool allow-list, terminal tool and round limit; the orchestrator enforces
 * them, so the SKILL files are configuration, not just prose.
 */
@Component
public class PromptRegistry {
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([a-z_]+)\\s*}}");
    public static final List<String> AGENTS = List.of("requirement-analyst", "task-planner", "media-executor", "quality-reviewer");

    public record Skill(String id, String title, String version, String agentNo, Set<String> tools,
                        String terminalTool, int maxRounds, String body) {}

    private final String systemTemplate;
    private final String systemVersion;
    private final String activeVersion;
    private final Map<String, Skill> skills = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    public PromptRegistry() {
        Map<String, Object> sys = (Map<String, Object>) new Yaml().load(read("ai/prompts/system.yaml"));
        this.systemTemplate = String.valueOf(sys.get("template"));
        this.systemVersion = String.valueOf(sys.get("version"));
        Map<String, Object> versions = (Map<String, Object>) new Yaml().load(read("ai/prompts/prompt-versions.yaml"));
        this.activeVersion = String.valueOf(versions.get("active"));
        if (!activeVersion.equals(systemVersion)) {
            throw new IllegalStateException("prompt-versions.yaml active=" + activeVersion + " but system.yaml version=" + systemVersion);
        }
        for (String id : AGENTS) skills.put(id, parseSkill(id, read("ai/skills/" + id + "/SKILL.md")));
    }

    public String activeVersion() { return activeVersion; }

    public Skill skill(String id) {
        Skill s = skills.get(id);
        if (s == null) throw new IllegalArgumentException("unknown skill " + id);
        return s;
    }

    public Collection<Skill> skills() { return skills.values(); }

    /** Renders the system prompt for one agent; unknown variables are left visible so they are easy to spot. */
    public String render(String skillId, Map<String, String> vars) {
        Skill s = skill(skillId);
        Map<String, String> all = new HashMap<>(vars);
        all.put("agent_id", "Agent " + s.agentNo() + " · " + s.id());
        all.put("agent_title", s.title());
        all.put("today", LocalDate.now().toString());
        all.put("skill", s.body());
        return substitute(systemTemplate, all);
    }

    static String substitute(String template, Map<String, String> vars) {
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static Skill parseSkill(String id, String text) {
        if (!text.startsWith("---")) throw new IllegalStateException("SKILL " + id + " has no front matter");
        int end = text.indexOf("\n---", 3);
        String front = text.substring(3, end);
        String body = text.substring(end + 4).trim();
        Map<String, String> fm = new HashMap<>();
        for (String line : front.split("\\R")) {
            int c = line.indexOf(':');
            if (c > 0) fm.put(line.substring(0, c).trim(), line.substring(c + 1).trim());
        }
        if (!id.equals(fm.get("id"))) throw new IllegalStateException("SKILL id mismatch for " + id);
        Set<String> tools = new LinkedHashSet<>();
        String t = fm.getOrDefault("tools", "");
        if (!t.startsWith("none") && !t.startsWith("(engine)")) {
            for (String x : t.split(",")) if (!x.isBlank()) tools.add(x.trim());
        }
        String terminal = fm.getOrDefault("terminal_tool", "none");
        return new Skill(id, fm.getOrDefault("title", id), fm.getOrDefault("version", "0"), fm.getOrDefault("agent", "?"),
                Collections.unmodifiableSet(tools), terminal.equals("none") ? null : terminal,
                Integer.parseInt(fm.getOrDefault("max_rounds", "1")), body);
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("missing prompt resource " + path, e);
        }
    }
}
