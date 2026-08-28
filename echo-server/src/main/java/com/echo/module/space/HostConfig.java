package com.echo.module.space;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 主控配置（TECH-P1 §2）：以 JSON 文本落 {@code MindSpace.hostConfig}。
 *
 * <p>字段：{@code broadcast}(是否对外广播)、{@code asyncOnly}(仅异步可见)、
 * {@code resonanceThreshold}(共鸣余弦距离阈值上限，越小越严格)、{@code allowBattle}(对赌 P3 占位)。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HostConfig {

    private boolean broadcast;

    private boolean asyncOnly;

    private double resonanceThreshold;

    private boolean allowBattle;

    /** P1 默认主控配置：对外广播、仅异步、阈值 0.5、不允许对赌。 */
    public static HostConfig defaults() {
        return new HostConfig(true, true, 0.5d, false);
    }
}
