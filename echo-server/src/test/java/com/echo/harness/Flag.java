package com.echo.harness;

/**
 * 数值痛点/红线旗标（EXP-BOTS §4 数值痛点旗标 · §4 红线告警）。
 *
 * @param severity  级别：RED（红线，优先于留存/营收）/ WARN（痛点）/ INFO（提示）
 * @param code      稳定码（便于机器判定/回归）
 * @param personaId 相关画像 id（大盘级旗标为 null）
 * @param message   人读说明
 */
public record Flag(Severity severity, String code, String personaId, String message) {

    /** 旗标级别。 */
    public enum Severity {
        /** 🚨 红线：二次伤害/挫败流失风险，触发数值回调。 */
        RED,
        /** 数值痛点：需关注调参。 */
        WARN,
        /** 提示信息。 */
        INFO
    }

    public static Flag red(String code, String personaId, String message) {
        return new Flag(Severity.RED, code, personaId, message);
    }

    public static Flag warn(String code, String personaId, String message) {
        return new Flag(Severity.WARN, code, personaId, message);
    }

    public boolean isRed() {
        return severity == Severity.RED;
    }
}
