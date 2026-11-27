package com.blog.relay;

import com.blog.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps relay business errors to real HTTP status codes using the Java response
 * envelope. Scoped to the relay JSON controllers on purpose: the OpenAI
 * compatible proxy has its own error shape.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {RelayController.class, RelayAdminController.class})
public class RelayExceptionHandler {

    @ExceptionHandler(RelayException.class)
    public ResponseEntity<ApiResponse<?>> handleRelayException(RelayException e) {
        HttpStatus status = e.getStatus();
        if (status.is5xxServerError()) {
            log.error("Relay request failed: {}", e.getMessage(), e);
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
