package com.houqing.tools.video_compressor_backend.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.config.AppProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Owns the whole task lifecycle for both modes: validation, bounded queuing, execution, real progress,
 * persistence, cancellation, retry, idempotency and restart recovery. Handlers only do processing.
 */
@Service
public class TaskEngine {
    private static final Logger log = LoggerFactory.getLogger(TaskEngine.class);
    private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING");

    private final AppProperties props;
    private final TaskRepository repo;
    private final StorageService storage;
    private final MediaProbe probe;
    private final ToolLocator tools;
    private final TaskEventHub events;
    private final ObjectMapper mapper;
    private final Map<String, MediaTaskHandler> handlers = new ConcurrentHashMap<>();
    private final Map<String, CancellationToken> running = new ConcurrentHashMap<>();

    private ThreadPoolExecutor executor;

    public TaskEngine(AppProperties props, TaskRepository repo, StorageService storage, MediaProbe probe,
                      ToolLocator tools, TaskEventHub events, ObjectMapper mapper, List<MediaTaskHandler> handlerBeans) {
        this.props = props;
        this.repo = repo;
        this.storage = storage;
        this.probe = probe;
        this.tools = tools;
        this.events = events;
        this.mapper = mapper;
        for (MediaTaskHandler h : handlerBeans) handlers.put(h.type(), h);
    }

    @PostConstruct
    void init() {
        int concurrency = Math.max(1, props.media().maxConcurrent());
        int queue = Math.max(1, props.media().queueCapacity());
        this.executor = new ThreadPoolExecutor(concurrency, concurrency, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queue), r -> {
            Thread t = new Thread(r, "media-task");
            t.setDaemon(true);
            return t;
        });
        this.executor.allowCoreThreadTimeOut(true);
        recoverInterrupted();
        log.info("TaskEngine ready: concurrency={}, queueCapacity={}, handlers={}", concurrency, queue, handlers.keySet());
    }

    @PreDestroy
    void shutdown() {
        if (executor != null) executor.shutdownNow();
        running.values().forEach(CancellationToken::cancel);
    }

    /** On startup, any task left QUEUED/RUNNING from a previous process is marked interrupted (and retryable). */
    void recoverInterrupted() {
        List<TaskEntity> stuck = repo.findByStatusIn(ACTIVE);
        long now = System.currentTimeMillis();
        for (TaskEntity t : stuck) {
            t.setStatus("FAILED");
            t.setErrorCode(ErrorCode.INTERRUPTED.name());
            t.setMessage(ErrorCode.INTERRUPTED.defaultMessage());
            t.setFinishedAt(now);
            t.setUpdatedAt(now);
            storage.deleteWorkDir(t.getId());
        }
        if (!stuck.isEmpty()) {
            repo.saveAll(stuck);
            log.info("marked {} interrupted task(s) as FAILED after restart", stuck.size());
        }
    }

    public MediaTaskHandler handler(String type) {
        MediaTaskHandler h = handlers.get(type);
        if (h == null) throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知的任务类型: " + type);
        return h;
    }

    // ---- submission ----

    /**
     * Not @Transactional on purpose: repo.save commits immediately, so the worker thread that picks the task up
     * can always read it (queuing inside an open transaction let a fast worker miss the row and skip the task).
     */
    public TaskEntity submit(long ownerId, String type, JsonNode params, List<String> inputFileIds,
                             String origin, String idempotencyKey) {
        MediaTaskHandler h = handler(type);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = repo.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey);
            if (existing.isPresent()) return existing.get();
        }
        for (Tool tool : h.requiredTools()) {
            if (!tools.available(tool)) {
                throw new ApiException(ErrorCode.TOOL_UNAVAILABLE, tool.label() + " 未安装，无法执行该操作");
            }
        }
        if (repo.countByOwnerIdAndStatusIn(ownerId, ACTIVE) >= props.media().perUserActiveLimit()) {
            throw new ApiException(ErrorCode.TOO_MANY_TASKS);
        }
        storage.ensureDiskSpace();

        List<MediaFile> inputs = inputFileIds.stream().map(id -> storage.require(ownerId, id)).toList();
        h.validate(params, inputs);

        if (executor.getQueue().remainingCapacity() == 0) throw new ApiException(ErrorCode.QUEUE_FULL);

        long now = System.currentTimeMillis();
        TaskEntity t = new TaskEntity();
        t.setId(java.util.UUID.randomUUID().toString());
        t.setOwnerId(ownerId);
        t.setType(type);
        t.setStatus("QUEUED");
        t.setProgress(0);
        t.setMessage("任务已提交，正在排队...");
        t.setParamsJson(writeJson(params));
        t.setInputFiles(writeJson(mapper.valueToTree(inputFileIds)));
        t.setOrigin(origin == null ? "PRO" : origin);
        t.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        try {
            repo.saveAndFlush(t);
        } catch (org.springframework.dao.DataAccessException e) {
            // two concurrent submits with the same Idempotency-Key: the unique index lets exactly one win.
            // (the SQLite dialect reports UNIQUE violations as a generic JpaSystemException, so catch broadly and
            // only swallow it when the winning row really exists)
            if (t.getIdempotencyKey() != null) {
                var winner = repo.findByOwnerIdAndIdempotencyKey(ownerId, t.getIdempotencyKey());
                if (winner.isPresent()) return winner.get();
            }
            throw e;
        }

        enqueue(t.getId(), inputs);
        return t;
    }

    private void enqueue(String taskId, List<MediaFile> inputs) {
        try {
            executor.execute(() -> runTask(taskId, inputs));
        } catch (RejectedExecutionException e) {
            failNow(taskId, ErrorCode.QUEUE_FULL);
            throw new ApiException(ErrorCode.QUEUE_FULL);
        }
    }

    // ---- execution ----

    private void runTask(String taskId, List<MediaFile> inputs) {
        MDC.put("taskId", taskId.substring(0, 8));
        CancellationToken token = new CancellationToken();
        running.put(taskId, token);
        Path workDir = null;
        try {
            TaskEntity t = repo.findById(taskId).orElse(null);
            if (t == null || "CANCELLED".equals(t.getStatus())) return;
            if (token.isCancelled()) { cancelFinalize(taskId); return; }

            markRunning(t);
            workDir = storage.newWorkDir(taskId);
            MediaTaskHandler h = handler(t.getType());
            JsonNode params = readJson(t.getParamsJson());
            TaskContextImpl ctx = new TaskContextImpl(taskId, t.getOwnerId(), params, inputs, workDir, token);

            MediaTaskHandler.HandlerResult result = h.execute(ctx);
            if (token.isCancelled()) { cancelFinalize(taskId); return; }
            complete(taskId, result);
        } catch (ApiException e) {
            if (e.getCode() == ErrorCode.CANCELLED) cancelFinalize(taskId);
            else fail(taskId, e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("task {} crashed", taskId, e);
            fail(taskId, ErrorCode.PROCESS_FAILED, ErrorCode.PROCESS_FAILED.defaultMessage());
        } finally {
            running.remove(taskId);
            if (workDir != null) storage.deleteWorkDir(taskId);
            MDC.remove("taskId");
        }
    }

    protected void markRunning(TaskEntity t) {
        long now = System.currentTimeMillis();
        t.setStatus("RUNNING");
        t.setStartedAt(now);
        t.setUpdatedAt(now);
        t.setMessage("正在处理...");
        repo.save(t);
        events.publish(t.getId(), "status", toView(t));
    }

    protected void complete(String taskId, MediaTaskHandler.HandlerResult result) {
        TaskEntity t = repo.findById(taskId).orElseThrow();
        long now = System.currentTimeMillis();
        t.setStatus("SUCCEEDED");
        t.setProgress(100);
        t.setStage("done");
        t.setMessage("处理完成");
        if (result.output() != null) t.setOutputFile(result.output().getId());
        if (result.summary() != null) t.setResultJson(result.summary().toString());
        t.setFinishedAt(now);
        t.setUpdatedAt(now);
        repo.save(t);
        events.publish(taskId, "status", toView(t));
        events.complete(taskId);
    }

    protected void fail(String taskId, ErrorCode code, String message) {
        TaskEntity t = repo.findById(taskId).orElse(null);
        if (t == null) return;
        long now = System.currentTimeMillis();
        t.setStatus("FAILED");
        t.setErrorCode(code.name());
        t.setMessage(message == null ? code.defaultMessage() : message);
        t.setFinishedAt(now);
        t.setUpdatedAt(now);
        repo.save(t);
        events.publish(taskId, "status", toView(t));
        events.complete(taskId);
    }

    protected void cancelFinalize(String taskId) {
        TaskEntity t = repo.findById(taskId).orElse(null);
        if (t == null) return;
        long now = System.currentTimeMillis();
        t.setStatus("CANCELLED");
        t.setErrorCode(ErrorCode.CANCELLED.name());
        t.setMessage("任务已取消");
        t.setFinishedAt(now);
        t.setUpdatedAt(now);
        repo.save(t);
        events.publish(taskId, "status", toView(t));
        events.complete(taskId);
    }

    protected void failNow(String taskId, ErrorCode code) {
        fail(taskId, code, code.defaultMessage());
    }

    // ---- control ----

    public TaskEntity cancel(long ownerId, String taskId) {
        TaskEntity t = require(ownerId, taskId);
        if (TaskStatus.valueOf(t.getStatus()).terminal()) throw new ApiException(ErrorCode.INVALID_TASK_STATE);
        CancellationToken token = running.get(taskId);
        if (token != null) {
            token.cancel();
        } else {
            // still queued: finalize immediately
            cancelFinalize(taskId);
        }
        return repo.findById(taskId).orElse(t);
    }

    public TaskEntity retry(long ownerId, String taskId) {
        TaskEntity t = require(ownerId, taskId);
        if (!"FAILED".equals(t.getStatus()) && !"CANCELLED".equals(t.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_TASK_STATE, "只有失败或已取消的任务可以重试");
        }
        for (Tool tool : handler(t.getType()).requiredTools()) {
            if (!tools.available(tool)) throw new ApiException(ErrorCode.TOOL_UNAVAILABLE, tool.label() + " 未安装");
        }
        if (repo.countByOwnerIdAndStatusIn(ownerId, ACTIVE) >= props.media().perUserActiveLimit()) {
            throw new ApiException(ErrorCode.TOO_MANY_TASKS);
        }
        storage.ensureDiskSpace();
        List<String> inputIds = readFileIds(t.getInputFiles());
        List<MediaFile> inputs = inputIds.stream().map(id -> storage.require(ownerId, id)).toList();

        long now = System.currentTimeMillis();
        t.setStatus("QUEUED");
        t.setProgress(0);
        t.setStage(null);
        t.setErrorCode(null);
        t.setMessage("任务已重新排队...");
        t.setOutputFile(null);
        t.setResultJson(null);
        t.setAttempt(t.getAttempt() + 1);
        t.setStartedAt(null);
        t.setFinishedAt(null);
        t.setUpdatedAt(now);
        repo.save(t);
        enqueue(taskId, inputs);
        return t;
    }

    /**
     * Blocks until the task reaches a terminal state or the timeout elapses (used by endpoints whose original
     * contract was synchronous, e.g. metadata removal). The work itself still runs on the bounded pool.
     */
    public TaskEntity awaitTerminal(long ownerId, String taskId, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            TaskEntity t = require(ownerId, taskId);
            if (TaskStatus.valueOf(t.getStatus()).terminal() || System.nanoTime() > deadline) return t;
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return t;
            }
        }
    }

    // ---- queries ----

    public TaskEntity require(long ownerId, String taskId) {
        return repo.findByIdAndOwnerId(taskId, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "任务不存在或无权访问"));
    }

    public Page<TaskEntity> list(long ownerId, int page, int size) {
        return list(ownerId, null, page, size);
    }

    /** statuses null/empty = all. */
    public Page<TaskEntity> list(long ownerId, List<String> statuses, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return statuses == null || statuses.isEmpty()
                ? repo.findByOwnerId(ownerId, pr)
                : repo.findByOwnerIdAndStatusIn(ownerId, statuses, pr);
    }

    public TaskView toView(TaskEntity t) {
        TaskView.OutputView out = null;
        if (t.getOutputFile() != null) {
            out = storage.find(t.getOwnerId(), t.getOutputFile())
                    .map(mf -> new TaskView.OutputView(mf.getId(), mf.getOriginalName(), mf.getSizeBytes(),
                            mf.getMediaType(), "/api/files/" + mf.getId() + "/content"))
                    .orElse(null); // output already expired and purged
        }
        JsonNode result = t.getResultJson() == null ? null : readJson(t.getResultJson());
        List<TaskView.InputView> ins = readFileIds(t.getInputFiles()).stream()
                .map(id -> storage.find(t.getOwnerId(), id)
                        .map(mf -> new TaskView.InputView(id, mf.getOriginalName(), mf.getMediaType()))
                        .orElse(new TaskView.InputView(id, null, null)))
                .toList();
        return new TaskView(t.getId(), t.getType(), t.getStatus(), t.getProgress(), t.getStage(), t.getMessage(),
                t.getErrorCode(), out == null ? null : out.fileId(), t.getOrigin(), t.getAttempt(), out, result,
                t.getCreatedAt(), t.getStartedAt(), t.getFinishedAt(), ins);
    }

    // ---- maintenance ----

    @Scheduled(fixedDelayString = "${app.cleanup-interval-ms:1800000}")
    public void scheduledCleanup() {
        try {
            storage.purgeExpired();
        } catch (Exception e) {
            log.warn("cleanup failed: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    private String writeJson(JsonNode node) { return node == null ? "{}" : node.toString(); }

    private JsonNode readJson(String s) {
        try {
            return mapper.readTree(s == null ? "{}" : s);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private List<String> readFileIds(String json) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Progress updates persist at most ~2/sec but always on stage change; SSE always fires. */
    private final class TaskContextImpl implements TaskContext {
        private final String taskId;
        private final long ownerId;
        private final JsonNode params;
        private final List<MediaFile> inputs;
        private final Path workDir;
        private final CancellationToken token;
        private volatile long lastPersist;
        private volatile String lastStage;

        TaskContextImpl(String taskId, long ownerId, JsonNode params, List<MediaFile> inputs, Path workDir,
                        CancellationToken token) {
            this.taskId = taskId;
            this.ownerId = ownerId;
            this.params = params;
            this.inputs = inputs;
            this.workDir = workDir;
            this.token = token;
        }

        @Override public String taskId() { return taskId; }
        @Override public long ownerId() { return ownerId; }
        @Override public JsonNode params() { return params; }
        @Override public List<MediaFile> inputs() { return inputs; }
        @Override public Path inputPath(MediaFile file) { return storage.pathOf(file); }
        @Override public Path workDir() { return workDir; }
        @Override public CancellationToken token() { return token; }
        @Override public MediaProbe.MediaInfo probe(MediaFile file) { return probe.probe(storage.pathOf(file)); }

        @Override
        public void progress(int percent, String stage) {
            int clamped = Math.max(0, Math.min(99, percent));
            boolean stageChanged = stage != null && !stage.equals(lastStage);
            long now = System.currentTimeMillis();
            events.publish(taskId, "progress", Map.of("taskId", taskId, "progress", clamped,
                    "stage", stage == null ? "" : stage));
            if (stageChanged || now - lastPersist > 500) {
                lastPersist = now;
                lastStage = stage;
                persistProgress(taskId, clamped, stage);
            }
        }
    }

    protected void persistProgress(String taskId, int percent, String stage) {
        TaskEntity t = repo.findById(taskId).orElse(null);
        if (t == null || !"RUNNING".equals(t.getStatus())) return;
        t.setProgress(percent);
        if (stage != null) t.setStage(stage);
        t.setUpdatedAt(System.currentTimeMillis());
        repo.save(t);
    }
}
