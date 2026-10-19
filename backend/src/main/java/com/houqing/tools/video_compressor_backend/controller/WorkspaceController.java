package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.auth.CurrentUser;
import com.houqing.tools.video_compressor_backend.media.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workspace overview. Every number is aggregated from the database for the current user; nothing is hard-coded. */
@RestController
@RequestMapping("/api/v2/workspace")
public class WorkspaceController {
    private final TaskRepository tasks;
    private final MediaFileRepository files;
    private final TaskEngine engine;
    private final UploadService uploads;

    public WorkspaceController(TaskRepository tasks, MediaFileRepository files, TaskEngine engine, UploadService uploads) {
        this.tasks = tasks;
        this.files = files;
        this.engine = engine;
        this.uploads = uploads;
    }

    public record Usage(long count, long bytes) {}

    public record Summary(Map<String, Long> tasksByStatus, Usage inputs, Usage outputs, Long nextExpiryAt,
                          List<TaskView> recentTasks, List<FileView> recentFiles) {}

    @GetMapping("/summary")
    public Summary summary() {
        long uid = CurrentUser.id();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TaskStatus s : TaskStatus.values()) byStatus.put(s.name(), 0L);
        for (Object[] row : tasks.countByStatus(uid)) byStatus.put((String) row[0], ((Number) row[1]).longValue());

        Usage in = new Usage(0, 0), out = new Usage(0, 0);
        for (Object[] row : files.usageByRole(uid)) {
            Usage u = new Usage(((Number) row[1]).longValue(), ((Number) row[2]).longValue());
            if ("INPUT".equals(row[0])) in = u;
            else if ("OUTPUT".equals(row[0])) out = u;
        }
        var page = org.springframework.data.domain.PageRequest.of(0, 5,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        List<TaskView> recentTasks = engine.list(uid, 0, 5).map(engine::toView).getContent();
        List<FileView> recentFiles = files.findByOwnerIdAndRole(uid, "INPUT", page).map(uploads::view).getContent();
        return new Summary(byStatus, in, out, files.nextExpiry(uid), recentTasks, recentFiles);
    }
}
