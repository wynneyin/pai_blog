package com.blog.shop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.blog.dto.ApiResponse;

/**
 * Maps shop business errors to real HTTP status codes. Scoped to the shop
 * controllers so the existing article endpoints keep their current behavior.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {ShopController.class, ShopAdminController.class})
public class ShopExceptionHandler {

    @ExceptionHandler(ShopException.class)
    public ResponseEntity<ApiResponse<?>> handleShopException(ShopException e) {
        HttpStatus status = e.getStatus();
        if (status.is5xxServerError()) {
            log.error("Shop request failed: {}", e.getMessage(), e);
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(status.value(), e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<?>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "请求参数不合法"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<?>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(400, "请求参数不合法"));
    }
}
