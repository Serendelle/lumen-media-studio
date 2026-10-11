package com.houqing.tools.video_compressor_backend.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Single place that turns exceptions into {code,message,details}; internal details only go to the log. */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> api(ApiException e) {
        return build(e.getCode(), e.getMessage(), e.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalid(MethodArgumentNotValidException e) {
        Map<String, Object> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> fields.putIfAbsent(f.getField(), f.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(g -> fields.putIfAbsent(g.getObjectName(), g.getDefaultMessage()));
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(), Map.of("fields", fields));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HandlerMethodValidationException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        log.debug("Bad request: {}", e.getMessage());
        return build(ErrorCode.VALIDATION_FAILED, "请求格式不正确", null);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException e) {
        return build(ErrorCode.FILE_TOO_LARGE, ErrorCode.FILE_TOO_LARGE.defaultMessage(), null);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> multipart(MultipartException e) {
        log.warn("Multipart error: {}", e.getMessage());
        return build(ErrorCode.VALIDATION_FAILED, "上传数据不完整，请重试", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return build(ErrorCode.VALIDATION_FAILED, "不支持的 Content-Type", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(new ErrorResponse("METHOD_NOT_ALLOWED", "请求方法不支持", null, null));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> noResource(NoResourceFoundException e) {
        return build(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage(), null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
        return build(ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception e) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error traceId={}", traceId, e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.defaultMessage(), null, traceId));
    }

    private ResponseEntity<ErrorResponse> build(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.status()).body(new ErrorResponse(code.name(), message, details, null));
    }
}
