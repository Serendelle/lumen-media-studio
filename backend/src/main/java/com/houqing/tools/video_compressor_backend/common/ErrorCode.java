package com.houqing.tools.video_compressor_backend.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "参数校验失败"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "请先登录"),
    BAD_CREDENTIALS(HttpStatus.UNAUTHORIZED, "用户名或密码错误"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "无权访问该资源"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    USERNAME_TAKEN(HttpStatus.CONFLICT, "用户名已被占用"),
    REGISTRATION_DISABLED(HttpStatus.FORBIDDEN, "当前未开放注册"),
    UNSUPPORTED_MEDIA(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的文件类型"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过大小限制"),
    MEDIA_UNREADABLE(HttpStatus.UNPROCESSABLE_ENTITY, "无法解析该媒体文件"),
    TARGET_UNREACHABLE(HttpStatus.UNPROCESSABLE_ENTITY, "处理目标无法实现"),
    CAPABILITY_UNSUPPORTED(HttpStatus.UNPROCESSABLE_ENTITY, "该格式不支持此操作"),
    /** The encode finished, but the measured output still exceeds the requested size. */
    TARGET_NOT_MET(HttpStatus.UNPROCESSABLE_ENTITY, "输出未达到目标体积"),
    AUDIO_STREAM_MISSING(HttpStatus.UNPROCESSABLE_ENTITY, "该文件不含音频轨道"),
    /** An independent re-check of the output (probe / decode / stream layout) contradicted the request. */
    OUTPUT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "输出文件未通过核验"),
    UNSUPPORTED_DOCUMENT(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "不支持的文档格式"),
    DOCUMENT_CORRUPTED(HttpStatus.UNPROCESSABLE_ENTITY, "文档已损坏或结构不合法"),
    DOCUMENT_PASSWORD_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "文档受密码保护，暂不支持处理加密文档"),
    DOCUMENT_CONVERSION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "文档转换失败"),
    DOCUMENT_CONVERSION_TIMEOUT(HttpStatus.UNPROCESSABLE_ENTITY, "文档转换超时"),
    CONVERTER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "文档转换引擎不可用"),
    INVALID_TASK_STATE(HttpStatus.CONFLICT, "当前任务状态不允许该操作"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "内容已被修改，请刷新后再保存"),
    DELIVERY_RUNNING(HttpStatus.CONFLICT, "该项目已有交付包正在生成"),
    TOO_MANY_TASKS(HttpStatus.TOO_MANY_REQUESTS, "进行中的任务过多，请稍后再试"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请稍后再试"),
    QUEUE_FULL(HttpStatus.SERVICE_UNAVAILABLE, "处理队列已满，请稍后再试"),
    INSUFFICIENT_DISK(HttpStatus.SERVICE_UNAVAILABLE, "服务器磁盘空间不足"),
    TOOL_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "依赖的处理工具未安装"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务未配置或暂不可用"),
    ASR_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "语音识别服务未配置或暂不可用"),
    ASR_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "语音识别失败"),
    LESSON_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "课程结构未通过校验"),
    PROCESS_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "处理失败"),
    PROCESS_TIMEOUT(HttpStatus.INTERNAL_SERVER_ERROR, "处理超时"),
    CANCELLED(HttpStatus.CONFLICT, "任务已取消"),
    INTERRUPTED(HttpStatus.INTERNAL_SERVER_ERROR, "服务重启导致任务中断"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() { return status; }
    public String defaultMessage() { return defaultMessage; }
}
