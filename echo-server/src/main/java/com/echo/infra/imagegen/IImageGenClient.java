package com.echo.infra.imagegen;

import java.util.List;

/** 定妆出图。实现只负责供应商调用，不碰建档状态。 */
public interface IImageGenClient {

    /** 未接真钥匙时为 false，建档继续走渐变色块。 */
    boolean isLive();

    /**
     * 以上传肖像为底出若干张定妆图。
     *
     * @param baseImageRef 公网 URL 或 {@code data:<mime>;base64,...}
     */
    List<GeneratedImage> stylize(String baseImageRef);
}
