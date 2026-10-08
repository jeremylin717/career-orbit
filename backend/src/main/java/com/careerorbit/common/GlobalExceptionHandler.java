package com.careerorbit.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：把各类异常统一转换为 ApiResponse 错误结构，避免把堆栈直接抛给前端。
 * 业务异常返回 400；参数校验返回 400 并带字段信息；未知异常返回 500 且不泄露内部细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> business(BusinessException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> illegal(RuntimeException ex) {
        return ApiResponse.error(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiResponse<Void> validation(MethodArgumentNotValidException ex) {
        var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
        return ApiResponse.error(error.map(e -> e.getField() + ": " + e.getDefaultMessage()).orElse("请求参数不合法"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ApiResponse<Void> unknown(Exception ex) {
        return ApiResponse.error("服务暂时不可用，请稍后重试");
    }
}
