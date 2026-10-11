package com.houqing.tools.video_compressor_backend.controller;

import com.houqing.tools.video_compressor_backend.common.ApiException;
import com.houqing.tools.video_compressor_backend.common.ErrorCode;
import com.houqing.tools.video_compressor_backend.media.TaskEngine;
import com.houqing.tools.video_compressor_backend.media.TaskEntity;
import com.houqing.tools.video_compressor_backend.media.TaskView;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shapes task results for the endpoints whose original contract was synchronous. */
final class TaskResponses {
    private TaskResponses() {}

    static Map<String, Object> created(TaskEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getId());
        m.put("status", t.getStatus());
        return m;
    }

    /** SUCCEEDED → 200 {downloadFilename, taskId, result}; FAILED → error; still running → 202 {taskId}. */
    static ResponseEntity<Map<String, Object>> syncResult(TaskEngine engine, TaskEntity t) {
        TaskView v = engine.toView(t);
        switch (t.getStatus()) {
            case "SUCCEEDED" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("downloadFilename", v.downloadFilename());
                m.put("taskId", v.taskId());
                m.put("output", v.output());
                m.put("result", v.result());
                return ResponseEntity.ok(m);
            }
            case "FAILED", "CANCELLED" -> {
                ErrorCode code;
                try {
                    code = ErrorCode.valueOf(t.getErrorCode());
                } catch (Exception e) {
                    code = ErrorCode.PROCESS_FAILED;
                }
                throw new ApiException(code, t.getMessage(), Map.of("taskId", t.getId()));
            }
            default -> {
                return ResponseEntity.accepted().body(created(t));
            }
        }
    }
}
