package com.blog.shop;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business exception carrying the real HTTP status that should be returned
 * to the client.
 */
@Getter
public class ShopException extends RuntimeException {

    private final HttpStatus status;

    public ShopException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static ShopException badRequest(String message) {
        return new ShopException(HttpStatus.BAD_REQUEST, message);
    }

    public static ShopException notFound(String message) {
        return new ShopException(HttpStatus.NOT_FOUND, message);
    }

    public static ShopException forbidden(String message) {
        return new ShopException(HttpStatus.FORBIDDEN, message);
    }

    public static ShopException conflict(String message) {
        return new ShopException(HttpStatus.CONFLICT, message);
    }

    public static ShopException internal(String message) {
        return new ShopException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
