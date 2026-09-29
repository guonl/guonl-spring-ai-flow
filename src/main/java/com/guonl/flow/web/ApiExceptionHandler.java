package com.guonl.flow.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API 统一异常出口：业务校验类错误返回400，其余返回500。
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.guonl.flow.web")
public class ApiExceptionHandler {

    @ExceptionHandler({IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException e) {
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> serverError(Exception e) {
        // 客户端断开（SSE 取消订阅/关标签页/管道截断）属正常现象，静默不刷错误日志
        if (e instanceof org.springframework.web.context.request.async.AsyncRequestNotUsableException) {
            log.info("[API] client disconnected: {}", e.getMessage());
            return null;
        }
        log.error("[API] unexpected error", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() == null ? "服务内部错误" : e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
