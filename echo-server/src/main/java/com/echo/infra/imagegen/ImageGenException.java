package com.echo.infra.imagegen;

/** 已接真出图但这一次没拿到图。调用方应让建档生成失败，而不是悄悄改回色块。 */
public final class ImageGenException extends RuntimeException {
    public ImageGenException(String message) {
        super(message);
    }

    public ImageGenException(String message, Throwable cause) {
        super(message, cause);
    }
}
