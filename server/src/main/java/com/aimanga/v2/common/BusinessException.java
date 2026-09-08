package com.aimanga.v2.common;

import lombok.Getter;

/**
 * 业务异常:message 直接面向用户展示;status 为随响应返回的 HTTP 状态码。
 */
@Getter
public class BusinessException extends RuntimeException {

    private final int status;

    public BusinessException(String message) {
        this(400, message);
    }

    public BusinessException(int status, String message) {
        super(message);
        this.status = status;
    }
}
