package com.houqing.tools.video_compressor_backend.common;

import java.util.Map;

/** Business exception whose message is safe to show to the client (never contains paths or tool output). */
public class ApiException extends RuntimeException {
    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code) { this(code, code.defaultMessage(), null); }
    public ApiException(ErrorCode code, String message) { this(code, message, null); }
    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode getCode() { return code; }
    public Map<String, Object> getDetails() { return details; }
}
