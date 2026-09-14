package com.echo.infra.imagegen;

/** 一张定妆结果。优先落盘后改成本地 url；供应商临时链只作回退。 */
public record GeneratedImage(String url, byte[] data, String contentType) {
}
