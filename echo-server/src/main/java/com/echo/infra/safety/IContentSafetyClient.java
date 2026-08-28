package com.echo.infra.safety;

/**
 * 内容安全服务客户端（第一关的外部依赖）。
 *
 * <p>实现：{@link HttpContentSafetyClient}（真实调用）、{@link FakeContentSafetyClient}
 * （可注入的假实现，把全链路测通而不发网络请求）。</p>
 *
 * <p>🔴 <b>实现约定：不要在实现里做失败兜底。</b>抛异常即可，
 * 失败方向由 {@link ContentSafetyGate} 统一收口成「未通过」。
 * 若每个实现各自决定失败怎么办，迟早有一个会写成 {@code catch { return passed(); }} ——
 * 那一行就让整关失效了，而且从调用方看不出来。</p>
 */
public interface IContentSafetyClient {

    /**
     * 文本检测。
     *
     * @return 服务的真实判定（{@link ContentSafetyVerdict.Outcome#PASSED} 或
     *         {@link ContentSafetyVerdict.Outcome#BLOCKED}）
     * @throws ContentSafetyException 超时 / 限流 / 鉴权失败 / 返回格式异常
     */
    ContentSafetyVerdict inspectText(String text) throws ContentSafetyException;

    /** 供应商标识，用于日志与指标维度。 */
    String provider();
}
