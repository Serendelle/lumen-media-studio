package com.houqing.tools.video_compressor_backend.service;

import com.houqing.tools.video_compressor_backend.dto.TaskProgressDTO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TaskService {
    // 使用 ConcurrentHashMap 来安全地存储任务状态
    private final Map<String, TaskProgressDTO> tasks = new ConcurrentHashMap<>();

    public TaskProgressDTO createTask() {
        String taskId = UUID.randomUUID().toString();
        TaskProgressDTO task = new TaskProgressDTO();
        task.setTaskId(taskId);
        task.setStatus("PENDING");
        tasks.put(taskId, task);
        return task;
    }

    public TaskProgressDTO getTask(String taskId) {
        return tasks.get(taskId);
    }

    public void updateTaskProgress(String taskId, int progress, String message) {
        TaskProgressDTO task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("PROCESSING");
            task.setProgress(progress);
            task.setMessage(message);
        }
    }

    public void completeTask(String taskId, String downloadFilename) {
        TaskProgressDTO task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("COMPLETED");
            task.setProgress(100);
            task.setMessage("压缩成功！");
            task.setDownloadFilename(downloadFilename);
        }
    }

    public void failTask(String taskId, String errorMessage) {
        TaskProgressDTO task = tasks.get(taskId);
        if (task != null) {
            task.setStatus("FAILED");
            task.setMessage(errorMessage);
        }
    }
}