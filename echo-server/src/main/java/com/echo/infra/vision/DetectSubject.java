package com.echo.infra.vision;

/**
 * 肖像识别到的<b>单个主体</b>（对齐契约 §2 detect 出参 {@code subjects[]} 的元素）。
 *
 * <p>一张肖像可识别出多个主体（如同框的狗与猫），故 {@link IVisionClient#detect(String)} 返回
 * 主体列表；调用方按 {@code confidence} 降序返回，前端在多主体时用 {@link #box} 叠加可点选框，
 * 强制用户先选定单一主体再确认。</p>
 *
 * @param subjectType 主体类型：动物 / 人 / 其他（当前建档主要面向动物，为将来扩展预留）
 * @param species     物种词，尽量对齐前端词表（狗/猫/兔/仓鼠/龙猫/豚鼠/刺猬/松鼠/鸟/鹦鹉/…/其他，
 *                    见 {@code echo-h5-proto} 的 {@code SPECIES_LIST}）；无法归类返回 {@code "其他"}
 * @param confidence  置信度，取值 {@code [0.0, 1.0]}
 * @param box         归一化位置框（相对肖像宽高，各分量 {@code [0,1]}）；单主体可为 {@code null}（省略）
 */
public record DetectSubject(SubjectType subjectType, String species, double confidence, Box box) {

    /** 便捷构造：无位置框的单主体。 */
    public static DetectSubject of(SubjectType subjectType, String species, double confidence) {
        return new DetectSubject(subjectType, species, confidence, null);
    }

    /**
     * 主体类型枚举。{@link #wire()} 为契约约定的线上字符串（{@code animal|person|other}）。
     */
    public enum SubjectType {
        ANIMAL("animal"),
        PERSON("person"),
        OTHER("other");

        private final String wire;

        SubjectType(String wire) {
            this.wire = wire;
        }

        /** 契约线上值（写入响应 JSON 的 {@code subjectType} 字段）。 */
        public String wire() {
            return wire;
        }
    }

    /**
     * 归一化位置框（相对肖像宽高，各分量取值 {@code [0,1]}）。供前端在图上叠加可点选框。
     *
     * @param x 左上角 x（相对宽）
     * @param y 左上角 y（相对高）
     * @param w 宽（相对宽）
     * @param h 高（相对高）
     */
    public record Box(double x, double y, double w, double h) {
    }
}
