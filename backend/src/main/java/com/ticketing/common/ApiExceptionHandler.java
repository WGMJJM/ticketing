package com.ticketing.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiResponse<Void>> business(BusinessException exception) {
        var response = ResponseEntity.status(exception.getStatus()).header("Cache-Control", "no-store");
        if ("PURCHASE_QUEUE_FULL".equals(exception.getCode())) response.header("Retry-After", "2");
        return response.body(ApiResponse.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiResponse<Void>> validation(BindException exception) {
        var error = exception.getBindingResult().getAllErrors().getFirst();
        String message = "请求参数不符合要求";
        // 绑定失败的默认信息可能含内部类型；仅采用显式中文校验消息。
        if (error.getDefaultMessage() != null && error.getDefaultMessage().matches(".*[\\p{IsHan}].*")) {
            message = error.getDefaultMessage();
        }
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", message));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.failure("INVALID_ARGUMENT", "请求参数格式不正确，请检查填写内容和允许的字段"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiResponse<Void>> integrity(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.failure("DATA_CONFLICT", "数据状态发生变化，请刷新后重试"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception exception) {
        LOG.error("处理请求发生内部错误", exception);
        return ResponseEntity.internalServerError().body(ApiResponse.failure("INTERNAL_ERROR", "服务暂时不可用，请稍后重试"));
    }
}
