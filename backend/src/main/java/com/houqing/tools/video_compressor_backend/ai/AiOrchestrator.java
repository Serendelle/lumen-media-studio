package com.houqing.tools.video_compressor_backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.houqing.tools.video_compressor_backend.ai.agent.AgentTools;
import com.houqing.tools.video_compressor_backend.ai.agent.MediaExecutor;
import com.houqing.tools.video_compressor_backend.ai.agent.PlanValidator;
import com.houqing.tools.video_compressor_backend.ai.agent.QualityReviewer;
import com.houqing.tools.video_compressor_backend.ai.llm.LlmClient;
import com.houqing.tools.video_compressor_backend.ai.prompt.PromptRegistry;
import com.houqing.tools.video_compressor_backend.ai.store.*;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.StorageService;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Multi-agent state machine for AI mode.
 *
 *   IDLE ─user msg─▶ ANALYZING (Agent 01, LLM + read-only tools)
 *        ├─ needs info ─▶ AWAITING_INPUT ─user msg─▶ ANALYZING
 *        └─ ready ─▶ PLANNING (Agent 02, LLM; plan validated server-side, ≤2 repairs)
 *                    ─▶ AWAITING_CONFIRMATION ─user confirms version N─▶ EXECUTING (Agent 03, deterministic)
 *                    ─▶ REVIEWING (Agent 04: independent checks, ≤1 safe retry, LLM explanation) ─▶ COMPLETED
 *   any stage ─error─▶ FAILED · user cancel ─▶ CANCELLED   (a new user message starts a new run)
 *
 * One model, four roles: each agent is a SKILL (prompt + tool allow-list + round limit) driven by this machine.
 * Every run has a hard budget of model calls; nothing is executed without the user's confirmation of the exact
 * plan version; every transition and tool call is a persisted, replayable event.
 */
@Service
public class AiOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AiOrchestrator.class);

    public static final List<String> BUSY = List.of("ANALYZING", "PLANNING", "EXECUTING", "REVIEWING");
    static final int MAX_LLM_CALLS_PER_RUN = 12;
    static final int MAX_PLAN_REPAIRS = 2;
    static final int HISTORY_MESSAGES = 12;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationEvents events;
    private final PromptRegistry prompts;
    private final LlmClient llm;
    private final AgentTools tools;
    private final PlanValidator validator;
    private final MediaExecutor executor;
    private final QualityReviewer reviewer;
    private final TaskEngine engine;
    private final StorageService storage;
    private final ObjectMapper mapper;

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();
    private ThreadPoolExecutor pool;

    public AiOrchestrator(ConversationRepository conversations, MessageRepository messages, ConversationEvents events,
                          PromptRegistry prompts, LlmClient llm, AgentTools tools, PlanValidator validator,
                          MediaExecutor executor, QualityReviewer reviewer, TaskEngine engine, StorageService storage,
                          ObjectMapper mapper) {
        this.conversations = conversations;
        this.messages = messages;
        this.events = events;
        this.prompts = prompts;
        this.llm = llm;
        this.tools = tools;
        this.validator = validator;
        this.executor = executor;
        this.reviewer = reviewer;
        this.engine = engine;
        this.storage = storage;
        this.mapper = mapper;
    }

    /** Per-run mutable state that is not worth persisting (cancellation, budget, tool cache). */
    static final class RunState {
        volatile boolean cancelled;
        final AtomicInteger llmCalls = new AtomicInteger();
        final Map<String, JsonNode> toolCache = new ConcurrentHashMap<>();
        final Set<String> runningTasks = ConcurrentHashMap.newKeySet();
        int promptTokens, completionTokens;
    }

    @PostConstruct
    void init() {
        pool = new ThreadPoolExecutor(4, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), r -> {
            Thread t = new Thread(r, "ai-agent");
            t.setDaemon(true);
            return t;
        });
        // runs interrupted by a restart: report honestly instead of leaving a spinner forever
        for (AiEntities.Conversation c : conversations.findByStateIn(BUSY)) {
            setState(c, "FAILED");
            events.emit(c.getId(), "error", Map.of("code", "INTERRUPTED", "message", "服务重启导致本次处理中断，请重新发送需求"));
        }
    }

    @PreDestroy
    void shutdown() {
        runs.values().forEach(r -> r.cancelled = true);
        if (pool != null) pool.shutdownNow();
    }

    // =================================================================== public API

    public AiEntities.Conversation create(long ownerId, String title) {
        AiEntities.Conversation c = new AiEntities.Conversation();
        c.setId(UUID.randomUUID().toString());
        c.setOwnerId(ownerId);
        c.setTitle(title == null || title.isBlank() ? "新的智能任务" : clip(title, 40));
        c.setState("IDLE");
        c.setPromptVersion(prompts.activeVersion());
        c.setContextJson("{}");
        long now = System.currentTimeMillis();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return conversations.save(c);
    }

    public AiEntities.Conversation require(long ownerId, String id) {
        return conversations.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "会话不存在或无权访问"));
    }

    public Page<AiEntities.Conversation> list(long ownerId, int page, int size) {
        return conversations.findByOwnerId(ownerId, PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "updatedAt")));
    }

    public List<AiEntities.Message> messages(String conversationId) {
        return messages.findByConversationIdOrderByIdAsc(conversationId);
    }

    /** A user message starts (or continues, after clarification) a run. */
    public AiEntities.Conversation send(long ownerId, String id, String text, List<String> fileIds) {
        if (!llm.configured()) throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 未配置：请在后端 .env 中设置 DEEPSEEK_API_KEY");
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_FAILED, "请输入你的需求");
        if (content.length() > 2000) throw new ApiException(ErrorCode.VALIDATION_FAILED, "需求描述不能超过 2000 字");
        AiEntities.Conversation c = require(ownerId, id);
        if (BUSY.contains(c.getState())) throw new ApiException(ErrorCode.INVALID_TASK_STATE, "上一条需求仍在处理中");
        if (conversations.countByOwnerIdAndStateIn(ownerId, BUSY) >= 2) throw new ApiException(ErrorCode.TOO_MANY_TASKS, "同时进行的智能会话过多");
        List<String> attached = new ArrayList<>();
        for (String f : fileIds == null ? List.<String>of() : fileIds) {
            storage.require(ownerId, f); // ownership check up front
            if (!attached.contains(f) && attached.size() < 10) attached.add(f);
        }
        ObjectNode ctx = context(c);
        ArrayNode att = mapper.createArrayNode();
        Set<String> merged = new LinkedHashSet<>();
        ctx.path("attachedFileIds").forEach(n -> merged.add(n.asText()));
        merged.addAll(attached);
        merged.forEach(att::add);
        ctx.set("attachedFileIds", att);
        c.setContextJson(ctx.toString());
        if ("新的智能任务".equals(c.getTitle())) c.setTitle(clip(content, 40));
        saveMessage(c.getId(), "user", null, content);
        setState(c, "ANALYZING");
        submit(c.getId(), () -> analyzeAndPlan(ownerId, c.getId()));
        return c;
    }

    /** Executes exactly the plan version the user saw. */
    public AiEntities.Conversation confirm(long ownerId, String id, int version, boolean approve) {
        AiEntities.Conversation c = require(ownerId, id);
        if (!"AWAITING_CONFIRMATION".equals(c.getState())) throw new ApiException(ErrorCode.INVALID_TASK_STATE, "当前没有待确认的计划");
        if (version != c.getPlanVersion()) throw new ApiException(ErrorCode.INVALID_TASK_STATE, "计划已更新，请确认最新版本");
        if (!approve) {
            saveMessage(c.getId(), "user", null, "（取消了这个计划）");
            message(c.getId(), "task-planner", "好的，计划已取消。你可以调整需求后重新告诉我。");
            setState(c, "IDLE");
            return c;
        }
        events.emit(c.getId(), "confirmed", Map.of("version", version));
        setState(c, "EXECUTING");
        submit(c.getId(), () -> executeAndReview(ownerId, c.getId()));
        return c;
    }

    public AiEntities.Conversation cancel(long ownerId, String id) {
        AiEntities.Conversation c = require(ownerId, id);
        RunState r = runs.get(id);
        if (r != null) {
            r.cancelled = true;
            for (String taskId : r.runningTasks) {
                try {
                    engine.cancel(ownerId, taskId);
                } catch (ApiException ignored) {
                    // already finished
                }
            }
        }
        if (BUSY.contains(c.getState()) || c.getState().startsWith("AWAITING")) {
            setState(c, "CANCELLED");
            events.emit(id, "agent", agent("all", "cancelled", "用户已取消"));
        }
        return c;
    }

    // =================================================================== stages

    private void analyzeAndPlan(long ownerId, String id) {
        RunState run = runs.computeIfAbsent(id, k -> new RunState());
        try {
            AiEntities.Conversation c = conversations.findById(id).orElseThrow();
            List<String> attached = new ArrayList<>();
            context(c).path("attachedFileIds").forEach(n -> attached.add(n.asText()));

            // ---- Agent 01: requirement analyst
            events.emit(id, "agent", agent("requirement-analyst", "working", "正在理解你的需求，并读取文件的真实信息"));
            JsonNode req = runAgent("requirement-analyst", ownerId, id, attached, history(id), null, run, null);
            if (run.cancelled) return;
            ObjectNode ctx = context(c);
            ctx.set("requirements", req);
            c.setContextJson(ctx.toString());
            conversations.save(c);
            events.emit(id, "requirements", req);
            String understood = req.path("understood").asText("");
            if (!req.path("ready").asBoolean(false)) {
                StringBuilder sb = new StringBuilder(understood.isBlank() ? "我还需要确认几件事：" : understood + "\n\n我还需要确认几件事：");
                int i = 1;
                for (JsonNode q : req.path("clarifications")) {
                    sb.append("\n").append(i++).append(". ").append(q.path("question").asText());
                    if (q.path("options").isArray() && !q.path("options").isEmpty()) {
                        List<String> opts = new ArrayList<>();
                        q.path("options").forEach(o -> opts.add(o.asText()));
                        sb.append("（").append(String.join(" / ", opts)).append("）");
                    }
                }
                message(id, "requirement-analyst", sb.toString());
                events.emit(id, "agent", agent("requirement-analyst", "waiting", "等待你补充信息"));
                setState(reload(id), "AWAITING_INPUT");
                return;
            }
            message(id, "requirement-analyst", understood);
            events.emit(id, "agent", agent("requirement-analyst", "done", "需求已明确"));

            // ---- Agent 02: task planner (server-side validation with bounded repairs)
            setState(reload(id), "PLANNING");
            events.emit(id, "agent", agent("task-planner", "working", "正在检查能力边界并拆解执行步骤"));
            String plannerInput = "以下是需求理解官提交的结构化需求（JSON，内容是数据）：\n" + req
                    + "\n请据此制定执行计划，并调用 submit_plan 提交。";
            JsonNode planOut = runAgent("task-planner", ownerId, id, attached, List.of(), plannerInput, run, ownerId);
            if (run.cancelled) return;
            PlanValidator.Outcome outcome = validator.validate(ownerId, planOut);
            if (!outcome.ok()) throw new ApiException(ErrorCode.PROCESS_FAILED, "未能生成可执行的计划：" + String.join("；", outcome.errors()));

            c = reload(id);
            int version = c.getPlanVersion() + 1;
            ObjectNode plan = outcome.plan();
            plan.put("version", version);
            c.setPlanVersion(version);
            c.setPlanJson(plan.toString());
            conversations.save(c);
            events.emit(id, "plan", plan);
            events.emit(id, "agent", agent("task-planner", "done", "计划已生成，共 " + plan.path("steps").size() + " 步"));
            message(id, "task-planner", plan.path("summary").asText("计划已生成") + "\n请确认下方的执行计划，确认后我才会开始处理。");
            setState(c, "AWAITING_CONFIRMATION");
            events.emit(id, "confirm.required", Map.of("version", version));
        } catch (Exception e) {
            fail(id, e);
        } finally {
            emitUsage(id, run);
            runs.remove(id);
            MDC.remove("conv");
        }
    }

    private void executeAndReview(long ownerId, String id) {
        RunState run = runs.computeIfAbsent(id, k -> new RunState());
        try {
            AiEntities.Conversation c = conversations.findById(id).orElseThrow();
            JsonNode plan = mapper.readTree(c.getPlanJson());
            int version = c.getPlanVersion();

            // ---- Agent 03: executor (deterministic)
            events.emit(id, "agent", agent("media-executor", "working", "按已确认的计划调用处理引擎"));
            MediaExecutor.Listener listener = u -> events.emit(id, "step", u);
            Map<Integer, MediaExecutor.StepResult> results = executor.run(ownerId, id, version, plan, listener,
                    () -> run.cancelled, run.runningTasks);
            if (run.cancelled) return;
            long failed = results.values().stream().filter(r -> !"SUCCEEDED".equals(r.status())).count();
            events.emit(id, "agent", agent("media-executor", failed == 0 ? "done" : "partial",
                    failed == 0 ? "全部步骤执行完成" : failed + " 个步骤未成功"));

            // ---- Agent 04: reviewer (independent checks + bounded retry, then explanation)
            setState(reload(id), "REVIEWING");
            events.emit(id, "agent", agent("quality-reviewer", "working", "重新读取输出文件，逐项核验结果"));
            QualityReviewer.Review review = reviewer.review(ownerId, id, version, plan, results, listener, () -> run.cancelled, run.runningTasks);
            if (run.cancelled) return;

            ArrayNode outputs = mapper.createArrayNode();
            for (MediaExecutor.StepResult r : review.results().values()) {
                ObjectNode o = outputs.addObject();
                o.put("stepId", r.stepId());
                o.put("tool", r.tool());
                o.put("status", r.status());
                if (r.taskId() != null) o.put("taskId", r.taskId());
                if (r.outputFileId() != null) {
                    o.put("fileId", r.outputFileId());
                    storage.find(ownerId, r.outputFileId()).ifPresent(f -> {
                        o.put("filename", f.getOriginalName());
                        o.put("sizeBytes", f.getSizeBytes());
                        o.put("mediaType", f.getMediaType());
                    });
                }
            }
            ObjectNode rv = mapper.createObjectNode();
            rv.set("checks", review.checks());
            rv.put("retries", review.retries());
            rv.put("allPassed", review.allPassed());
            rv.set("outputs", outputs);
            c = reload(id);
            ObjectNode ctx = context(c);
            ctx.set("review", rv);
            c.setContextJson(ctx.toString());
            conversations.save(c);
            events.emit(id, "review", rv);

            String explanation = explain(id, plan, rv, run);
            message(id, "quality-reviewer", explanation);
            events.emit(id, "agent", agent("quality-reviewer", "done", review.allPassed() ? "核验通过" : "部分核验未通过"));
            setState(reload(id), "COMPLETED");
        } catch (Exception e) {
            fail(id, e);
        } finally {
            emitUsage(id, run);
            runs.remove(id);
        }
    }

    // =================================================================== agent loop

    /**
     * Drives one agent: system prompt from its SKILL, tool allow-list from its front matter, at most max_rounds
     * model calls, ends only through its terminal tool. Disallowed tools are refused; identical tool calls are
     * answered from cache; the whole run shares a hard model-call budget.
     */
    private JsonNode runAgent(String skillId, long ownerId, String convId, List<String> attached,
                              List<AiEntities.Message> history, String extraUser, RunState run, Long validateFor) throws Exception {
        PromptRegistry.Skill skill = prompts.skill(skillId);
        ArrayNode msgs = mapper.createArrayNode();
        msgs.addObject().put("role", "system").put("content", prompts.render(skillId, Map.of(
                "files", tools.filesSummary(ownerId, attached),
                "capabilities", tools.capabilitiesSummary())));
        for (AiEntities.Message m : history) {
            msgs.addObject().put("role", m.getRole().equals("user") ? "user" : "assistant").put("content", m.getContent());
        }
        if (extraUser != null) msgs.addObject().put("role", "user").put("content", extraUser);
        ArrayNode toolDefs = tools.definitions(skill.tools());
        int repairs = 0;

        for (int round = 1; round <= skill.maxRounds(); round++) {
            if (run.cancelled) throw new ApiException(ErrorCode.CANCELLED);
            if (run.llmCalls.incrementAndGet() > MAX_LLM_CALLS_PER_RUN) {
                throw new ApiException(ErrorCode.PROCESS_FAILED, "AI 调用次数超过上限，已停止以控制成本");
            }
            LlmClient.Result res = llm.chat(msgs, toolDefs, "auto",
                    delta -> events.emitTransient(convId, "text.delta", Map.of("agent", skillId, "delta", delta)));
            run.promptTokens += res.promptTokens();
            run.completionTokens += res.completionTokens();

            ObjectNode assistant = msgs.addObject().put("role", "assistant");
            assistant.put("content", res.content() == null ? "" : res.content());
            if (!res.hasToolCalls()) {
                if (res.content() != null && !res.content().isBlank()) events.emit(convId, "thought", Map.of("agent", skillId, "text", clip(res.content(), 600)));
                msgs.addObject().put("role", "user").put("content", "请调用 " + skill.terminalTool() + " 提交结构化结果。");
                continue;
            }
            ArrayNode tc = assistant.putArray("tool_calls");
            for (LlmClient.ToolCall call : res.toolCalls()) {
                ObjectNode c = tc.addObject().put("id", call.id()).put("type", "function");
                c.putObject("function").put("name", call.name()).put("arguments", call.arguments() == null ? "{}" : call.arguments());
            }

            JsonNode terminalResult = null;
            for (LlmClient.ToolCall call : res.toolCalls()) {
                JsonNode args = LlmClient.args(mapper, call);
                String name = call.name();
                JsonNode result;
                if (!skill.tools().contains(name)) {
                    result = tools.error("工具 " + name + " 不在 " + skill.title() + " 的权限范围内");
                } else if (args == null) {
                    result = tools.error("参数不是合法的 JSON");
                } else if (name.equals(skill.terminalTool())) {
                    if (validateFor != null) {
                        PlanValidator.Outcome o = validator.validate(ownerId, args);
                        if (!o.ok() && repairs < MAX_PLAN_REPAIRS) {
                            repairs++;
                            result = mapper.createObjectNode().put("accepted", false).set("errors", mapper.valueToTree(o.errors()));
                            events.emit(convId, "tool.done", toolEvent(skillId, call, false, "计划校验未通过，第 " + repairs + " 次修正：" + clip(String.join("；", o.errors()), 300)));
                        } else {
                            result = mapper.createObjectNode().put("accepted", o.ok());
                            terminalResult = args;
                        }
                    } else {
                        result = mapper.createObjectNode().put("accepted", true);
                        terminalResult = args;
                    }
                } else {
                    events.emit(convId, "tool.start", toolEvent(skillId, call, true, null));
                    result = tools.run(name, args, ownerId, run.toolCache);
                    events.emit(convId, "tool.done", toolEvent(skillId, call, !result.has("error"), summarize(name, result)));
                }
                msgs.addObject().put("role", "tool").put("tool_call_id", call.id())
                        .put("content", AgentTools.truncate(result.toString()));
            }
            if (terminalResult != null) return terminalResult;
        }
        throw new ApiException(ErrorCode.PROCESS_FAILED, prompts.skill(skillId).title() + "未能在限定轮数内给出结果，请换个说法再试");
    }

    /** Agent 04's explanation: model writes it from the verified checks; a deterministic summary is the fallback. */
    private String explain(String convId, JsonNode plan, JsonNode review, RunState run) {
        String fallback = fallbackSummary(review);
        if (run.llmCalls.incrementAndGet() > MAX_LLM_CALLS_PER_RUN) return fallback;
        try {
            ArrayNode msgs = mapper.createArrayNode();
            msgs.addObject().put("role", "system").put("content", prompts.render("quality-reviewer", Map.of(
                    "files", "（略）", "capabilities", tools.capabilitiesSummary())));
            msgs.addObject().put("role", "user").put("content", "已确认的计划：" + plan + "\n\n服务端核验结果（只使用这里的数字）：" + review
                    + "\n\n请按你的职责写出给用户的说明。");
            LlmClient.Result res = llm.chat(msgs, null, null,
                    delta -> events.emitTransient(convId, "text.delta", Map.of("agent", "quality-reviewer", "delta", delta)));
            run.promptTokens += res.promptTokens();
            run.completionTokens += res.completionTokens();
            return res.content() == null || res.content().isBlank() ? fallback : res.content().trim();
        } catch (Exception e) {
            log.warn("reviewer explanation failed, using fallback: {}", e.getMessage());
            return fallback;
        }
    }

    static String fallbackSummary(JsonNode review) {
        StringBuilder sb = new StringBuilder(review.path("allPassed").asBoolean() ? "全部核验通过。" : "部分结果未达到要求：");
        for (JsonNode c : review.path("checks")) {
            if (!c.path("ok").asBoolean()) {
                sb.append("\n· 步骤 ").append(c.path("stepId").asInt()).append(" ").append(c.path("name").asText())
                        .append("：期望 ").append(c.path("expected").asText()).append("，实际 ").append(c.path("actual").asText());
            }
        }
        sb.append("\n结果文件可以在下方下载。");
        return sb.toString();
    }

    // =================================================================== helpers

    private void submit(String id, Runnable r) {
        try {
            pool.execute(() -> {
                MDC.put("conv", id.substring(0, 8));
                r.run();
            });
        } catch (RejectedExecutionException e) {
            fail(id, new ApiException(ErrorCode.QUEUE_FULL, "AI 处理队列已满，请稍后再试"));
            throw new ApiException(ErrorCode.QUEUE_FULL, "AI 处理队列已满，请稍后再试");
        }
    }

    private void fail(String id, Exception e) {
        RunState run = runs.get(id);
        AiEntities.Conversation c = conversations.findById(id).orElse(null);
        if (c == null) return;
        if (run != null && run.cancelled) {
            setState(c, "CANCELLED");
            return;
        }
        String code = e instanceof ApiException ae ? ae.getCode().name() : "INTERNAL_ERROR";
        String msg = e instanceof ApiException ae ? ae.getMessage() : "处理过程中出现了意外错误";
        if (!(e instanceof ApiException)) log.error("AI run {} crashed", id, e);
        events.emit(id, "error", Map.of("code", code, "message", msg));
        message(id, "system", "出错了：" + msg);
        setState(c, "FAILED");
    }

    private void emitUsage(String id, RunState run) {
        if (run.llmCalls.get() > 0) {
            events.emit(id, "usage", Map.of("llmCalls", run.llmCalls.get(), "promptTokens", run.promptTokens,
                    "completionTokens", run.completionTokens, "model", llm.model()));
        }
    }

    private void setState(AiEntities.Conversation c, String state) {
        c.setState(state);
        c.setUpdatedAt(System.currentTimeMillis());
        conversations.save(c);
        events.emit(c.getId(), "state", Map.of("state", state));
    }

    private AiEntities.Conversation reload(String id) {
        return conversations.findById(id).orElseThrow();
    }

    private void saveMessage(String convId, String role, String agent, String content) {
        AiEntities.Message m = new AiEntities.Message();
        m.setConversationId(convId);
        m.setRole(role);
        m.setAgent(agent);
        m.setContent(content);
        m.setCreatedAt(System.currentTimeMillis());
        m = messages.save(m);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", m.getId());
        payload.put("role", role);
        if (agent != null) payload.put("agent", agent);
        payload.put("content", content);
        payload.put("createdAt", m.getCreatedAt());
        events.emit(convId, "message", payload);
    }

    private void message(String convId, String agent, String content) {
        saveMessage(convId, "assistant", agent, content);
    }

    private List<AiEntities.Message> history(String id) {
        List<AiEntities.Message> all = messages.findByConversationIdOrderByIdAsc(id);
        List<AiEntities.Message> relevant = all.stream().filter(m -> !"system".equals(m.getAgent())).toList();
        return relevant.subList(Math.max(0, relevant.size() - HISTORY_MESSAGES), relevant.size());
    }

    private ObjectNode context(AiEntities.Conversation c) {
        try {
            JsonNode n = mapper.readTree(c.getContextJson() == null ? "{}" : c.getContextJson());
            return n.isObject() ? (ObjectNode) n : mapper.createObjectNode();
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private Map<String, Object> agent(String id, String status, String note) {
        return Map.of("agent", id, "status", status, "note", note);
    }

    private ObjectNode toolEvent(String agent, LlmClient.ToolCall call, boolean ok, String summary) {
        ObjectNode n = mapper.createObjectNode();
        n.put("agent", agent);
        n.put("callId", call.id());
        n.put("name", call.name());
        n.put("args", clip(call.arguments() == null ? "{}" : call.arguments(), 300));
        n.put("ok", ok);
        if (summary != null) n.put("summary", summary);
        return n;
    }

    /** Short, human-readable result line for the UI timeline. */
    private static String summarize(String tool, JsonNode r) {
        if (r.has("error")) return "失败：" + r.path("error").asText();
        return switch (tool) {
            case "list_files" -> "找到 " + r.path("files").size() + " 个文件";
            case "inspect_media" -> r.path("kind").asText() + " · " + r.path("sizeMB").asText() + " MB"
                    + (r.has("width") ? " · " + r.path("width").asInt() + "×" + r.path("height").asInt() : "")
                    + (r.has("durationSec") ? " · " + r.path("durationSec").asText() + " 秒" : "");
            case "read_metadata" -> (r.path("hasGps").asBoolean() ? "含 GPS 位置" : "无 GPS") + " · 敏感字段 " + r.path("sensitiveTags").size() + " 项";
            case "estimate_compression" -> r.path("feasible").asBoolean()
                    ? "可行：约 " + r.path("videoKbps").asLong() + " kbps"
                    : "不可行：" + r.path("reason").asText() + "（最小约 " + r.path("minFeasibleMB").asText() + " MB）";
            case "get_capabilities" -> "可用 " + r.path("available").size() + " 项能力";
            default -> "完成";
        };
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
