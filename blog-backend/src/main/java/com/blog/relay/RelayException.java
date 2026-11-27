package com.blog.relay;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Business exception carrying the real HTTP status that should be returned to
 * the client. The message is the exact contract text shown in the response.
 */
@Getter
public class RelayException extends RuntimeException {

    private final HttpStatus status;

    public RelayException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static RelayException badRequest(String message) {
        return new RelayException(HttpStatus.BAD_REQUEST, message);
    }

    public static RelayException unauthorized(String message) {
        return new RelayException(HttpStatus.UNAUTHORIZED, message);
    }

    public static RelayException forbidden(String message) {
        return new RelayException(HttpStatus.FORBIDDEN, message);
    }

    public static RelayException notFound(String message) {
        return new RelayException(HttpStatus.NOT_FOUND, message);
    }

    public static RelayException conflict(String message) {
        return new RelayException(HttpStatus.CONFLICT, message);
    }

    public static RelayException tooManyRequests(String message) {
        return new RelayException(HttpStatus.TOO_MANY_REQUESTS, message);
    }

    public static RelayException internal(String message) {
        return new RelayException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
