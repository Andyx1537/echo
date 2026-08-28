package com.echo.infra.llm;

/**
 * LLM 补全客户端抽象（对应 TECH-P1 §4.1 / BE-4）。
 *
 * <p>意识档案生成流程中，用于把用户自拟的少量原始偏好扩写/补足为结构化偏好集。
 * 抽象成接口便于切换供应商、控频次/超时，并便于单测 mock（外部服务一律 mock）。</p>
 *
 * <p>本期（BE-1）仅定义占位签名，真实 HTTP 实现待 BE-4 选型后落地。</p>
 */
public interface ILlmClient {

    /**
     * 基于原始偏好做 LLM 补全。
     *
     * @param rawPrefs 用户自拟的原始偏好（JSON 文本占位）
     * @return 补全后的结构化偏好（JSON 文本占位）；失败时实现方应兜底返回原始偏好
     */
    String enrich(String rawPrefs);

    /**
     * 通用补全：给定完整 prompt，返回模型输出（约定为 JSON 文本）。
     *
     * <p>为体验 Bot 定性层（{@code com.echo.harness} §7 LLM 定性 bot）新增。默认返回空 JSON
     * 对象 {@code "{}"} 作为兜底，使得只依赖 {@link #enrich(String)} 的既有实现无需改动即可编译；
     * 真实供应商实现与 Mock 可按需覆盖本方法。</p>
     *
     * @param prompt 完整提示词
     * @return 模型输出（约定 JSON 文本）；默认兜底为 {@code "{}"}
     */
    default String complete(String prompt) {
        return "{}";
    }
}
