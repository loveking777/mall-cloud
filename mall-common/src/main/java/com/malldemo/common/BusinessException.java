package com.malldemo.common;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。
 *
 * 和普通 RuntimeException 的区别：
 * 它自带 HTTP 状态码，能被 GlobalExceptionHandler 翻译成
 * 对应状态码 + 统一响应体，而不是一律 500。
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;

    public BusinessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
