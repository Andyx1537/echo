package com.aengine.persistence.dialect;

/**
 * 当前方言不支持某项特性。
 *
 * <p>🔴 这个异常存在的意义是<b>让不支持在装配期就炸</b>，而不是产出一条
 * 语义被悄悄削弱的 SQL。见 {@link Feature} 的类注释。</p>
 */
public class UnsupportedFeatureException extends RuntimeException {

    private final transient Feature feature;
    private final String dialect;

    public UnsupportedFeatureException(String dialect, Feature feature, String context) {
        super("方言 [" + dialect + "] 不支持 " + feature + "；出现位置：" + context
                + "。不要降级处理——降级会让约束静默失效，见 Feature 类注释。");
        this.dialect = dialect;
        this.feature = feature;
    }

    public Feature getFeature() {
        return feature;
    }

    public String getDialect() {
        return dialect;
    }
}
