package com.echo.infra.llm;

import lombok.extern.slf4j.Slf4j;

/**
 * {@link ILlmClient} 的假实现：直接回传输入，不做任何补全。
 *
 * <p>用于 P1 主流程独立联调与单测（符合 Aengine “外部服务一律 mock” 约定）。</p>
 */
@Slf4j
public class MockLlmClient implements ILlmClient {

    @Override
    public String enrich(String rawPrefs) {
        log.debug("MockLlmClient.enrich 原样回传, rawPrefs={}", rawPrefs);
        return rawPrefs;
    }

    /**
     * 定性层兜底：返回一个"温和安全"的固定评审 JSON，便于 {@code LlmBotReviewer} 在无真实供应商时联调。
     */
    @Override
    public String complete(String prompt) {
        if (prompt != null && prompt.startsWith("private-pet-onboarding\n")) {
            return "开发流程预览：从熟悉的日常，慢慢认出它（真实画面生成尚未接入）";
        }
        log.debug("MockLlmClient.complete 返回固定评审 JSON, promptLen={}", prompt == null ? 0 : prompt.length());
        return "{\"scores\":{\"emotionalSafety\":4,\"authenticity\":4,\"monotony\":1,"
                + "\"surprise\":3,\"griefIntensity\":2,\"intrusion\":0,\"personaFit\":4},"
                + "\"redFlag\":false,\"comment\":\"mock: 温暖安全，基调稳妥。\"}";
    }
}
