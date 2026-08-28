package com.echo.infra.provenance;

/**
 * 文件元数据隐式标识的七个要素（{@code GB 45438—2025 附录 E}，规范性附录）。
 *
 * <p>《人工智能生成合成内容标识办法》第五条要求在生成合成内容的<b>文件元数据</b>中添加隐式标识；
 * {@code GB 45438—2025} 是<b>强制性</b>国标，附录 E 把「具体写哪些字段、写成什么样」定死了。
 * 本类是那个格式的唯一来源，🔴 <b>不要在别处手拼这段 JSON</b>。</p>
 *
 * <p><b>七个要素</b>（附录 E c)~i)）：{@code Label} 生成合成标签 · {@code ContentProducer}
 * 生成合成服务提供者 · {@code ProduceID} 内容制作编号 · {@code ReservedCode1} 预留字段1（安全防护）·
 * {@code ContentPropagator} 内容传播服务提供者 · {@code PropagateID} 内容传播编号 ·
 * {@code ReservedCode2} 预留字段2。</p>
 *
 * <p>🔴 <b>首次写入时传播要素必须与制作要素一致</b>（附录 E 注1）。这不是可选的默认值，
 * 是标准的规定，所以只暴露 {@link #firstWrite} 这一个工厂，不给「自己随便填传播要素」的入口——
 * 我们是生成方，不是传播方，首次写入之外的场景在 Echo 不存在。</p>
 *
 * <p>🔴 <b>各要素的值用「编码」而不是「名称」。</b>附录 E j) 要求值主要由 {@code GB18030—2022}
 * 码位 {@code 0x21}、{@code 0x23~0x5B}、{@code 0x5D~0x7E} 的字符构成——那是<b>不含空格、引号、反斜杠的
 * 单字节可打印字符</b>，中文名称落在这个集合之外。名称与编码标准里二选一，选编码就不用赌
 * 「应主要由」这个措辞有多宽。</p>
 */
public record AigcLabel(
        String label,
        String contentProducer,
        String produceId,
        String reservedCode1,
        String contentPropagator,
        String propagateId,
        String reservedCode2) {

    /** 属于人工智能生成合成内容（附录 E c) 1)）。 */
    public static final String LABEL_GENERATED = "1";
    /** 可能为人工智能生成合成内容。 */
    public static final String LABEL_POSSIBLY = "2";
    /** 疑似为人工智能生成合成内容。 */
    public static final String LABEL_SUSPECTED = "3";

    /** 元数据扩展字段的关键词。附录 E a)：字段名称或关键词中应包含「AIGC」。 */
    public static final String FIELD_KEYWORD = "AIGC";

    public AigcLabel {
        requireLabel(label);
        contentProducer = requireElement(contentProducer, "ContentProducer");
        produceId = requireElement(produceId, "ProduceID");
        contentPropagator = requireElement(contentPropagator, "ContentPropagator");
        propagateId = requireElement(propagateId, "PropagateID");
        reservedCode1 = sanitize(reservedCode1 == null ? "" : reservedCode1);
        reservedCode2 = sanitize(reservedCode2 == null ? "" : reservedCode2);
    }

    /**
     * 生成方首次写入。🔴 传播要素与制作要素<b>置为一致</b>，照附录 E 注1。
     *
     * @param producerCode 生成合成服务提供者编码（{@link ProviderCode}）
     * @param produceId    我方对该内容的唯一编号
     */
    public static AigcLabel firstWrite(String producerCode, String produceId) {
        return new AigcLabel(LABEL_GENERATED, producerCode, produceId, "", producerCode, produceId, "");
    }

    /** 带预留字段1 的首次写入（附录 F.4：可放元数据的数字签名，用于保护标识完整性）。 */
    public AigcLabel withReservedCode1(String value) {
        return new AigcLabel(label, contentProducer, produceId, value,
                contentPropagator, propagateId, reservedCode2);
    }

    /**
     * 序列化成附录 E b) 规定的那一个字符串。
     *
     * <p>🔴 <b>逐字照抄标准里的形状</b>（含 {@code "AIGC":} 之后的那个空格与要素顺序）。
     * 用手写拼接而不是交给 JSON 库：库会按自己的习惯决定空格、转义与键序，
     * 而这里的输出形状本身就是被规范约束的东西，不能随库的版本漂移。</p>
     */
    public String toMetadataValue() {
        return "{\"" + FIELD_KEYWORD + "\": {"
                + "\"Label\":\"" + label + "\","
                + "\"ContentProducer\":\"" + contentProducer + "\","
                + "\"ProduceID\":\"" + produceId + "\","
                + "\"ReservedCode1\":\"" + reservedCode1 + "\","
                + "\"ContentPropagator\":\"" + contentPropagator + "\","
                + "\"PropagateID\":\"" + propagateId + "\","
                + "\"ReservedCode2\":\"" + reservedCode2 + "\""
                + "}}";
    }

    private static void requireLabel(String v) {
        if (!LABEL_GENERATED.equals(v) && !LABEL_POSSIBLY.equals(v) && !LABEL_SUSPECTED.equals(v)) {
            throw new IllegalArgumentException(
                    "Label 只能是 1（属于）/2（可能）/3（疑似），见 GB 45438 附录 E c)：" + v);
        }
    }

    private static String requireElement(String v, String name) {
        String s = sanitize(v == null ? "" : v);
        if (s.isEmpty()) {
            // 🔴 空着比不写更糟：文件看起来带了标识，核验时却什么都取不到
            throw new IllegalArgumentException(name + " 不可为空（GB 45438 附录 E 规定的必填要素）");
        }
        return s;
    }

    /**
     * 按附录 E j) 收敛字符集：只留 {@code 0x21}、{@code 0x23~0x5B}、{@code 0x5D~0x7E}。
     *
     * <p>被排除的正是空格、{@code "} 与 {@code \}——前者不在允许码位里，后两者是会把这段 JSON
     * 撑破的字符。标准允许用 {@code \"} 转义表示引号，但我们的值全部是自己生成的编码，
     * 没有任何需要引号的场景，所以这里<b>直接丢弃而不是转义</b>：能不产生转义就不产生。</p>
     */
    static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == 0x21 || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
