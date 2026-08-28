package com.echo.http;

/**
 * 路由处理函数：接收 {@link RequestContext}，返回将被包进成功信封 {@code {code:0,data}} 的 data 对象；
 * 业务失败抛 {@link ApiException}。
 */
@FunctionalInterface
public interface Route {
    Object handle(RequestContext ctx) throws Exception;
}
