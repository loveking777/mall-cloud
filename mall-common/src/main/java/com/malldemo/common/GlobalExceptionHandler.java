package com.malldemo.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局异常处理。
 *
 * 放在 mall-common 里，各服务通过 scanBasePackages 引入即可复用。
 *
 * 目的：不管成功还是失败，接口都返回同一种结构：
 * { "code": 400, "message": "用户名已存在", "data": null }
 * 前端只需要写一套解析逻辑。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * @Valid 校验失败：取第一条错误信息返回。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("参数校验失败");

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, message));
    }

    /**
     * 路径参数、查询参数上的约束校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
            ConstraintViolationException ex) {

        String message = ex.getConstraintViolations()
                .stream()
                .findFirst()
                .map(violation -> violation.getMessage())
                .orElse("参数校验失败");

        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, message));
    }

    /**
     * 业务异常：状态码由异常自己决定。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex) {

        HttpStatus status = ex.getStatus();

        return ResponseEntity
                .status(status)
                .body(ApiResponse.error(
                        status.value(),
                        ex.getMessage()
                ));
    }

    /**
     * 请求体不是合法 JSON，或字段类型不匹配。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(
            HttpMessageNotReadableException ex) {

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(
                        400,
                        "请求参数格式错误"
                ));
    }

    /**
     * 代码里主动抛出的 ResponseStatusException。
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleStatusException(
            ResponseStatusException ex) {

        HttpStatus status = HttpStatus.valueOf(
                ex.getStatusCode().value());

        String message = ex.getReason() == null
                ? "请求失败"
                : ex.getReason();

        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), message));
    }

    /**
     * 兜底处理。
     *
     * 先判断异常是不是 Spring 自带的"带状态码异常"：
     * 例如路径不存在（404）、缺少请求头（400）、方法不支持（405）。
     * 这些异常都实现了 ErrorResponse 接口，能拿到正确状态码，
     * 直接按 500 返回会误导调用方。
     *
     * 真正没预料到的异常才记日志并返回 500，
     * 详细信息只写日志，不返回给客户端。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOtherException(
            Exception ex) {

        if (ex instanceof ErrorResponse errorResponse) {

            HttpStatus status = HttpStatus.valueOf(
                    errorResponse.getStatusCode().value());

            String detail = errorResponse.getBody().getDetail();

            String message = detail == null
                    ? status.getReasonPhrase()
                    : detail;

            return ResponseEntity.status(status)
                    .body(ApiResponse.error(status.value(), message));
        }

        log.error("未处理的异常", ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(500, "服务器内部错误"));
    }
}
