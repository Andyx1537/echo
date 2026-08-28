package com.echo.infra.provenance;

import java.util.function.Function;

/**
 * 隐式标识配置（环境变量装配，风格同 {@code StorageConfig}/{@code VisionConfig}）。
 *
 * <ul>
 *   <li>{@code ECHO_AIGC_PROVIDER_CODE} —— 生成合成服务提供者编码，27 位，见 {@link ProviderCode}。
 *       🔴 <b>没有默认值</b>：这个编码要由合规侧登记后填进来，代码编不出合法值。</li>
 * </ul>
 *
 * <p>🔴 <b>未配置时 {@link #ready()} 为 false，生成侧应当据此拒绝产出媒体</b>，而不是产出一个没标识的文件。
 * 见 {@link GeneratedMediaPublisher}。</p>
 */
public record ProvenanceConfig(String providerCode) {

    public static ProvenanceConfig fromEnv() {
        return from(System::getenv);
    }

    public static ProvenanceConfig from(Function<String, String> env) {
        String code = env.apply("ECHO_AIGC_PROVIDER_CODE");
        return new ProvenanceConfig(code == null ? "" : code.trim());
    }

    /** 是否已具备写入合法隐式标识的前提。 */
    public boolean ready() {
        return ProviderCode.isValid(providerCode);
    }
}
