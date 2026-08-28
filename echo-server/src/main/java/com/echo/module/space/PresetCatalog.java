package com.echo.module.space;

/**
 * 定势预设清单（ART.md §3）。P1 起步只跑通 1001/1002 两个，其余号段预留。
 *
 * <p>"意识空间"由 Self Vector 选中一个定势预设为骨架；本类提供 presetSetId 常量与
 * 起步阶段的简单选择映射。维度细化、向量标签权重等后续由策划在 template 配置表维护。</p>
 */
public final class PresetCatalog {

    /** 1001 黄昏老楼道（默认）。 */
    public static final long DUSK_CORRIDOR = 1001L;

    /** 1002 午后旧书房。 */
    public static final long STUDY_ROOM = 1002L;

    /** 1003 雨后街角（预留）。 */
    public static final long RAINY_STREET = 1003L;

    /** 1004 旧教室（预留）。 */
    public static final long OLD_CLASSROOM = 1004L;

    /** 1005 老车站月台（预留）。 */
    public static final long STATION_PLATFORM = 1005L;

    /** 1006 夏夜庭院（预留）。 */
    public static final long SUMMER_COURTYARD = 1006L;

    /** 起步默认定势。 */
    public static final long DEFAULT_PRESET = DUSK_CORRIDOR;

    private PresetCatalog() {
    }

    /**
     * P1 起步的简单定势选择：按个人向量的归一化哈希在 1001/1002 间二选一；无向量时用默认 1001。
     *
     * <p>这是占位映射，待 template 配置表 + 向量标签权重落地后替换为真实匹配。</p>
     *
     * @param normHash 个人向量归一化哈希（{@code SelfVector.normHash}），可空
     * @return presetSetId（1001 或 1002）
     */
    public static long choosePreset(String normHash) {
        if (normHash == null || normHash.isBlank()) {
            return DEFAULT_PRESET;
        }
        return (Math.floorMod(normHash.hashCode(), 2) == 0) ? DUSK_CORRIDOR : STUDY_ROOM;
    }
}
