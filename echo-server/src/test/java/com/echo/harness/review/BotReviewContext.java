package com.echo.harness.review;

import com.echo.harness.BotPersona;

/**
 * 定性评审输入（EXP-BOTS §7.2 输入）：画像设定 + 当前温度档 + 实际回声文案 payload。
 *
 * @param persona     被评审画像（提供 voiceProfile / grief / sensitivity 等视角）
 * @param currentTemp 当前温度（用于推导温度档）
 * @param echoText    实际生成的回声文案（往宠近况/动态）
 */
public record BotReviewContext(BotPersona persona, double currentTemp, String echoText) {

    /** 温度档（§3.11 分档）：鲜活 90–100 / 安好 75–90 / 想你 60–75。 */
    public enum TempBand {
        VIVID,   // 90–100 鲜活
        STABLE,  // 75–90 安好
        MISSING  // 60–75 想你（地板区）
    }

    /** 当前温度所属档位。 */
    public TempBand band() {
        if (currentTemp >= 90.0) {
            return TempBand.VIVID;
        }
        if (currentTemp >= 75.0) {
            return TempBand.STABLE;
        }
        return TempBand.MISSING;
    }
}
