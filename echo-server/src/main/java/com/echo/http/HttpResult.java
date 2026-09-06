package com.echo.http;

/** HTTP status plus data for endpoints whose success status is part of the public contract. */
public record HttpResult(int status, Object data) {
    public static HttpResult created(Object data) {
        return new HttpResult(201, data);
    }

    public static HttpResult accepted(Object data) {
        return new HttpResult(202, data);
    }
}
