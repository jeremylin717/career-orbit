package com.careerorbit.common;

import java.time.Instant;

/**
 * 统一响应结构。所有接口都返回它，前端据此判断成功与否并取 data。
 *
 * @param success 是否成功
 * @param data    业务数据（失败时为 null）
 * @param message 失败原因（成功时为 null）
 * @param timestamp 服务端时间戳
 */
public record ApiResponse<T>(boolean success, T data, String message, Instant timestamp) {

    /** 成功响应。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    /** 失败响应。 */
    public static ApiResponse<Void> error(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}
