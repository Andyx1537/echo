package com.echo.infra.provenance;

/**
 * 生成合成服务提供者编码（《网络安全标准实践指南——人工智能生成合成内容标识 服务提供者编码规则》，
 * 网安秘字〔2025〕29 号）。
 *
 * <p>二十七位，阿拉伯数字或<b>大写</b>英文字母：</p>
 * <table>
 *   <tr><td>第 1~2 位</td><td>标识格式定义码，按本指南编制的固定为 {@code 00}</td></tr>
 *   <tr><td>第 3~22 位</td><td>主体标识码（20 位）</td></tr>
 *   <tr><td>第 23~27 位</td><td>服务扩展码（5 位）；不编写时填 {@code 00000}。
 *       编写时第 23 位为服务类型（生成合成 {@code 1} / 内容传播 {@code 2}），
 *       第 24~27 位为模型/应用码，由服务提供者自行编写</td></tr>
 * </table>
 *
 * <p>🔴 <b>主体标识码不能由代码编出来。</b>它是主体登记信息派生的，必须由合规侧提供并配置进来。
 * 所以本类只做校验，不做「生成一个能用的编码」——一个自动编出来的编码在监管侧是无效的，
 * 而它长得跟真的一样，正好骗过我们自己。</p>
 */
public final class ProviderCode {

    /** 编码总长度。 */
    public static final int LENGTH = 27;
    /** 按本指南编制时固定的标识格式定义码。 */
    public static final String FORMAT_PREFIX = "00";
    /** 不编写服务扩展码时的填充值。 */
    public static final String NO_SERVICE_EXT = "00000";
    /** 服务扩展码第 23 位：生成合成服务。 */
    public static final char SERVICE_GENERATION = '1';
    /** 服务扩展码第 23 位：内容传播服务。 */
    public static final char SERVICE_PROPAGATION = '2';

    private ProviderCode() {
    }

    /** 该字符串是否是一个格式合法的服务提供者编码。 */
    public static boolean isValid(String code) {
        if (code == null || code.length() != LENGTH || !code.startsWith(FORMAT_PREFIX)) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验并返回，不合法直接抛。
     *
     * <p>🔴 <b>刻意不提供「不合法就回退到一个占位编码」的路径。</b>写进文件的编码是要被监管核验的，
     * 占位编码写进去等于声明了一个不存在的主体——比不写更糟，因为文件从外面看是「已标识」的。</p>
     */
    public static String require(String code) {
        if (!isValid(code)) {
            throw new IllegalStateException(
                    "服务提供者编码不合法（应为 27 位数字或大写字母、前两位 00）："
                            + (code == null ? "null" : "\"" + code + "\"")
                            + "。此编码须由合规侧按《服务提供者编码规则》登记后配置 ECHO_AIGC_PROVIDER_CODE，"
                            + "🔴 不得由代码自行编造。");
        }
        return code;
    }

    /**
     * 用主体标识码拼一个编码。
     *
     * @param subjectCode 主体标识码，20 位数字或大写字母（由合规侧提供）
     * @param serviceExt  服务扩展码，5 位；传 null 表示不编写，填 {@link #NO_SERVICE_EXT}
     */
    public static String of(String subjectCode, String serviceExt) {
        if (subjectCode == null || subjectCode.length() != 20) {
            throw new IllegalArgumentException("主体标识码应为 20 位：" + subjectCode);
        }
        String ext = serviceExt == null ? NO_SERVICE_EXT : serviceExt;
        if (ext.length() != 5) {
            throw new IllegalArgumentException("服务扩展码应为 5 位：" + serviceExt);
        }
        return require(FORMAT_PREFIX + subjectCode + ext);
    }
}
