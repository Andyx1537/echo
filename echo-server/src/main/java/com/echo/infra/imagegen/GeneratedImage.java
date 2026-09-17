package com.echo.infra.imagegen;

/** 一张定妆结果。供应商地址只作拉取源；展示地址必须是落盘后的本方 url。 */
public record GeneratedImage(String url, byte[] data, String contentType) {
}
