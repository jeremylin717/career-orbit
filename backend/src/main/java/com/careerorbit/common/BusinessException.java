package com.careerorbit.common;

/** 业务异常：用于表达可预期的业务错误（会被全局处理器转成 400 + 提示信息）。 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
