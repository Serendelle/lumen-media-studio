package com.houqing.tools.video_compressor_backend.media;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Deletes personal history without removing files or records referenced by AI and project audit trails. */
@Service
public class TaskHistoryService {
    private final TaskRepository tasks;

    public TaskHistoryService(TaskRepository tasks) { this.tasks = tasks; }

    public record DeleteResult(int deletedCount, List<String> taskIds) {}
    public record ClearResult(int deletedCount, List<String> taskIds, long clearedBefore) {}

    public TaskEntity requireVisible(long ownerId, String id) {
        return tasks.findByIdAndOwnerIdAndHistoryDeletedAtIsNull(id, ownerId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "任务不存在、已删除或无权访问"));
    }

    @Transactional
    public DeleteResult delete(long ownerId, List<String> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 200
                || requested.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "请选择 1-200 条历史记录");
        }
        List<String> ids = List.copyOf(new LinkedHashSet<>(requested));
        try {
            List<TaskEntity> selected = tasks.findByOwnerIdAndIdInAndHistoryDeletedAtIsNull(ownerId, ids);
            if (selected.size() != ids.size())
                throw new ApiException(ErrorCode.NOT_FOUND, "部分任务不存在、已删除或无权访问");
            if (selected.stream().anyMatch(t -> !TaskStatus.valueOf(t.getStatus()).terminal()))
                throw new ApiException(ErrorCode.INVALID_TASK_STATE, "进行中的任务不能删除历史记录，请先取消或等待完成");
            int deleted = tasks.hideHistory(ownerId, ids, System.currentTimeMillis());
            if (deleted != ids.size()) {
                // A concurrent retry/deletion won the state transition. Roll back the entire batch.
                throw new ApiException(ErrorCode.INVALID_TASK_STATE, "任务状态已变化，请刷新历史记录后重试");
            }
            return new DeleteResult(deleted, ids);
        } catch (RuntimeException e) {
            throw concurrencyOrOriginal(e);
        }
    }

    @Transactional
    public ClearResult clear(long ownerId) {
        long cutoff = System.currentTimeMillis();
        try {
            int deleted = tasks.clearHistory(ownerId, cutoff);
            // Return a snapshot cutoff rather than loading potentially unbounded task IDs into memory.
            return new ClearResult(deleted, List.of(), cutoff);
        } catch (RuntimeException e) {
            throw concurrencyOrOriginal(e);
        }
    }

    /** SQLite WAL snapshot upgrades are often wrapped as generic JPA exceptions, not lock exceptions. */
    private static RuntimeException concurrencyOrOriginal(RuntimeException error) {
        if (error instanceof ApiException) return error;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            boolean conflict = cause instanceof ConcurrencyFailureException;
            if (cause instanceof SQLException sql) {
                // Standard serialization failure, PostgreSQL deadlock and lock-not-available states.
                String state = sql.getSQLState();
                conflict |= "40001".equals(state) || "40P01".equals(state) || "55P03".equals(state);
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase(Locale.ROOT);
            conflict |= message.contains("sqlite_busy") || message.contains("sqlite_locked")
                    || message.contains("database is locked") || message.contains("database table is locked");
            if (conflict) return new ApiException(ErrorCode.INVALID_TASK_STATE, "任务状态或历史记录正在变化，请刷新后重试");
        }
        // Connection errors, invalid SQL and other database failures must retain their normal diagnostics.
        return error;
    }
}
