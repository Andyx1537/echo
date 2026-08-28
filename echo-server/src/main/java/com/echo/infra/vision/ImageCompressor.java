package com.echo.infra.vision;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * 送给视觉模型前的<b>图片瘦身</b>：缩边 + 重编码 JPEG，把 base64 后的体积压到阈值内。
 *
 * <p>为什么必须压：肖像原件动辄 1~2MB，base64 后再涨 1/3，整包上行几 MB 时百炼侧单次调用要
 * 一分钟以上，必然撞穿 {@link ApiVisionClient} 的超时，用户看到的就是「AI 每次都认成狗」。
 * 缩到最长边 {@value #DEFAULT_MAX_EDGE}px 后识别精度对「认物种」这件事完全够用，耗时降到秒级
 * （实测数据见 {@link #DEFAULT_MAX_EDGE}）。</p>
 *
 * <p>只用 JDK 自带 {@code javax.imageio}，<b>不引入任何第三方依赖</b>（项目铁律）。</p>
 *
 * <p><b>安全回退</b>：解码/编码失败（异形格式、损坏字节、无可用编码器）一律不抛异常，
 * 原样返回入参字节，由上层决定是照发还是走 fallback。</p>
 */
@Slf4j
public final class ImageCompressor {

    /**
     * 缩放后的最长边（px）。
     *
     * <p>取 768 而非 1024 是<b>实测</b>结果：qwen-vl 的耗时几乎全在图片视觉 token 的 prefill 上，
     * 同一张猫照片 1024px（1153 prompt tokens）要 10~13s，768px（705 tokens）约 5s，
     * 512px（385 tokens）约 3.5s，而识别结果都是「猫 / 0.98」——认物种这件事对分辨率并不敏感。
     * 768 是「稳稳低于 10s 又留足识别余量」的折中；可用 {@code ECHO_VISION_MAX_EDGE} 调。</p>
     */
    public static final int DEFAULT_MAX_EDGE = 768;

    /** JPEG 编码质量（0~1）。 */
    public static final float DEFAULT_QUALITY = 0.85f;

    /** base64 后的目标上限（字节）。 */
    public static final int DEFAULT_MAX_BASE64_BYTES = 500 * 1024;

    /** 超限时的重试次数：交替降质量与降尺寸。 */
    private static final int MAX_ATTEMPTS = 5;

    /** 尺寸下限：再小就影响识别了。 */
    private static final int MIN_EDGE = 320;

    /** 质量下限。 */
    private static final float MIN_QUALITY = 0.4f;

    private ImageCompressor() {
    }

    /**
     * 压缩结果。
     *
     * @param data       压缩后字节（失败时为原始字节）
     * @param mime       对应 MIME（压缩成功为 {@code image/jpeg}）
     * @param compressed 是否真的压过（false 表示走了安全回退，原样返回）
     */
    public record Image(byte[] data, String mime, boolean compressed) {

        /** base64 编码后的字节数（不实际编码，按 4/3 向上取整估算，与 {@code Base64} 无填充差异一致）。 */
        public int base64Size() {
            return 4 * ((data.length + 2) / 3);
        }
    }

    /** 按默认参数压缩（最长边 {@value #DEFAULT_MAX_EDGE}px、质量 {@value #DEFAULT_QUALITY}）。 */
    public static Image compress(byte[] data, String mime) {
        return compress(data, mime, DEFAULT_MAX_EDGE, DEFAULT_QUALITY, DEFAULT_MAX_BASE64_BYTES);
    }

    /**
     * 缩边 + 重编码，直到 base64 体积落到 {@code maxBase64Bytes} 内或退无可退。
     *
     * @param data           原始图片字节
     * @param mime           原始 MIME（仅在回退时原样带出）
     * @param maxEdge        缩放后最长边（px）
     * @param quality        JPEG 起始质量（0~1）
     * @param maxBase64Bytes base64 后的目标上限（字节）
     * @return 压缩结果；任何失败都返回原始字节的 {@link Image}（{@code compressed=false}），不抛异常
     */
    public static Image compress(byte[] data, String mime, int maxEdge, float quality, int maxBase64Bytes) {
        if (data == null || data.length == 0) {
            return new Image(data, mime, false);
        }
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(data));
            if (src == null) {
                log.warn("图片压缩跳过：ImageIO 无法解码该格式，原样发送, mime={}, size={}B", mime, data.length);
                return new Image(data, mime, false);
            }
            int edge = Math.max(MIN_EDGE, maxEdge);
            float q = clampQuality(quality);
            byte[] best = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                byte[] jpeg = toJpeg(scaleToMaxEdge(src, edge), q);
                if (jpeg == null) {
                    break;
                }
                best = jpeg;
                if (base64Size(jpeg) <= maxBase64Bytes) {
                    break;
                }
                // 交替降质量 / 降尺寸：先削质量（视觉损失更小），再削尺寸
                if (attempt % 2 == 0) {
                    q = Math.max(MIN_QUALITY, q - 0.15f);
                } else {
                    edge = Math.max(MIN_EDGE, edge * 3 / 4);
                }
            }
            if (best == null) {
                log.warn("图片压缩失败：无 JPEG 编码器可用，原样发送, mime={}, size={}B", mime, data.length);
                return new Image(data, mime, false);
            }
            log.debug("图片压缩完成: {}B → {}B（base64 约 {}B）, maxEdge={}, quality={}",
                    data.length, best.length, base64Size(best), edge, q);
            return new Image(best, "image/jpeg", true);
        } catch (Exception e) {
            log.warn("图片压缩异常，原样发送, mime={}, size={}B, msg={}", mime, data.length, e.toString());
            return new Image(data, mime, false);
        }
    }

    /** base64 编码后的字节数估算。 */
    public static int base64Size(byte[] data) {
        return 4 * ((data.length + 2) / 3);
    }

    /** 等比缩放到最长边不超过 {@code maxEdge}；本来就够小则原图返回。 */
    private static BufferedImage scaleToMaxEdge(BufferedImage src, int maxEdge) {
        int w = src.getWidth();
        int h = src.getHeight();
        int longest = Math.max(w, h);
        int tw = w;
        int th = h;
        if (longest > maxEdge) {
            double ratio = (double) maxEdge / longest;
            tw = Math.max(1, (int) Math.round(w * ratio));
            th = Math.max(1, (int) Math.round(h * ratio));
        }
        // 即便不缩放也要重绘到 TYPE_INT_RGB：JPEG 不支持透明通道，PNG 的 alpha 直接编码会串色
        BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // 透明区域铺白，避免变成黑块
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, tw, th);
            g.drawImage(src, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    /** 按指定质量编码 JPEG；无可用编码器返回 {@code null}。 */
    private static byte[] toJpeg(BufferedImage image, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static float clampQuality(float q) {
        if (q < MIN_QUALITY) {
            return MIN_QUALITY;
        }
        return Math.min(q, 1.0f);
    }
}
