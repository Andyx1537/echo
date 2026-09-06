package com.echo.http;

import java.util.Map;

/**
 * 业务异常：携带契约错误码段（1xxx 鉴权 / 2xxx 参数 / 3xxx 业务规则 / 5xxx 服务端）与
 * 面向用户的温柔文案（构造时即过 {@link CopyGuardFilter}，确保错误提示也守词表——定案 #6）。
 */
public class ApiException extends RuntimeException {

    /** 常用错误码（对齐 API-CONTRACT §0 错误码段）。 */
    public static final int UNAUTHORIZED = 1001;

    /**
     * {@code S1′}：已鉴权但<b>未完成可验证绑定</b>，这个动作做不了。
     *
     * <p>🔴 <b>必须与 {@link #UNAUTHORIZED} 分成两个码</b>，否则前端分不出
     * 「通行证过期了，重新取一张」和「这一步要先绑手机号」——⚠️ 前者的正确处置是
     * <b>重新取 token</b>，后者的正确处置是<b>进绑定流程</b>。混在一个码上，
     * 前端的全局拦截器会把后者当成前者，于是<b>清掉游客 token、再发一张、再被拦</b>，
     * 转成一个用户看不懂的循环。</p>
     *
     * <p>📌 HTTP 状态是 <b>403</b> 而不是 401（见 {@code HttpGateway.httpStatusOf}）：
     * 游客<b>是</b>已鉴权的，只是不被允许——401 会触发前端既有的重登录逻辑。</p>
     */
    public static final int BINDING_REQUIRED = 1002;
    public static final int BAD_PARAM = 2001;
    public static final int RULE_QUOTA_EXCEEDED = 3001;
    public static final int RULE_FORBIDDEN = 3002;
    public static final int NOT_FOUND = 2004;
    public static final int GONE = 2410;
    public static final int SERVER_ERROR = 5000;

    private final int code;
    private final String detail;
    private final Map<String, Object> data;

    public ApiException(int code, String userMsg) {
        this(code, userMsg, null);
    }

    public ApiException(int code, String userMsg, String detail) {
        this(code, userMsg, detail, null);
    }

    public ApiException(int code, String userMsg, String detail, Map<String, Object> data) {
        super(CopyGuardFilter.sanitize(userMsg));
        this.code = code;
        this.detail = detail;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public Map<String, Object> data() {
        return data;
    }
}
