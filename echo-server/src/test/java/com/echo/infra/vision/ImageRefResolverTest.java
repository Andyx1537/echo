package com.echo.infra.vision;

import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.LocalDiskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源解析（{@link StorageImageRefResolver}）与发送前压缩（{@link ImageCompressor}）单测。
 *
 * <p>覆盖缺陷根因：{@code resourceId} 必须被解析成模型可消费的引用（本地存储 → data-uri，
 * 有公网 URL → 直传），且 base64 后体积压到阈值内——原样把 resourceId 塞进 {@code image_url}
 * 正是「永远认成狗」的病根，发原图则必然撞超时。不发起任何真实网络调用。</p>
 */
class ImageRefResolverTest {

    /** 本地存储：读字节 → 压缩 → data-uri，且体积落在阈值内。 */
    @Test
    void localStorageResolvesToCompressedDataUri(@TempDir Path dir) throws IOException {
        IStorage storage = new LocalDiskStorage(dir.toString(), "");
        byte[] png = photoLikePng(3000, 2000);
        IStorage.Stored stored = storage.put("8823393053208561601", png, "image/png", "cat.png");
        // 原图确实是「大图」，压缩才有意义
        assertThat(png.length).isGreaterThan(500 * 1024);

        String ref = new StorageImageRefResolver(storage).resolve(stored.resourceId());

        assertThat(ref).isNotNull();
        assertThat(ref).startsWith("data:image/jpeg;base64,");
        String base64 = ref.substring(ref.indexOf(',') + 1);
        assertThat(base64.length()).isLessThan(ImageCompressor.DEFAULT_MAX_BASE64_BYTES);
        // 解回来仍是一张能解码的图，且最长边已缩到 1024
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        assertThat(decoded).isNotNull();
        assertThat(Math.max(decoded.getWidth(), decoded.getHeight())).isEqualTo(ImageCompressor.DEFAULT_MAX_EDGE);
    }

    /** 存储能给出公网 URL（OSS/CDN 场景）时直传 URL，不读字节、不内联 base64。 */
    @Test
    void publicUrlIsPassedThroughWithoutInlining() {
        IStorage cdn = new IStorage() {
            @Override
            public Stored put(String resourceId, byte[] data, String contentType, String filename) {
                return new Stored(resourceId, resourceId + ".jpg", "https://cdn.example.com/" + resourceId + ".jpg");
            }

            @Override
            public Loaded load(String key) {
                throw new AssertionError("有公网 URL 时不应回读字节");
            }

            @Override
            public String externalUrl(String resourceId) {
                return "https://cdn.example.com/" + resourceId + ".jpg";
            }
        };

        String ref = new StorageImageRefResolver(cdn).resolve("res-1");

        assertThat(ref).isEqualTo("https://cdn.example.com/res-1.jpg");
    }

    /** 本地存储的 {@code /api/v1/files/...} 对外部供应商不可达，必须内联而不是直传该地址。 */
    @Test
    void localDownloadUrlIsNotTreatedAsPublic(@TempDir Path dir) throws IOException {
        IStorage relative = new LocalDiskStorage(dir.toString(), "");
        IStorage loopback = new LocalDiskStorage(dir.toString(), "http://127.0.0.1:8080");
        IStorage lan = new LocalDiskStorage(dir.toString(), "http://192.168.1.9:8080");
        byte[] png = photoLikePng(64, 64);
        for (IStorage s : new IStorage[]{relative, loopback, lan}) {
            s.put("res-local", png, "image/png", "p.png");
            assertThat(s.externalUrl("res-local")).isNull();
        }

        IStorage published = new LocalDiskStorage(dir.toString(), "https://cdn.echo.example.com");
        published.put("res-local", png, "image/png", "p.png");
        assertThat(published.externalUrl("res-local")).isEqualTo("https://cdn.echo.example.com/api/v1/files/res-local.png");
    }

    /** 素材不存在 / resourceId 为空 → 返回 null（由上层走 fallback），不抛异常。 */
    @Test
    void missingResourceResolvesToNullWithoutThrowing(@TempDir Path dir) {
        IImageRefResolver resolver = new StorageImageRefResolver(new LocalDiskStorage(dir.toString(), ""));
        assertThat(resolver.resolve("not-exists")).isNull();
        assertThat(resolver.resolve("")).isNull();
        assertThat(resolver.resolve(null)).isNull();
    }

    /** 上层直接给外链/data-uri 时原样透传（联调直传场景）。 */
    @Test
    void directRefIsPassedThrough(@TempDir Path dir) {
        IImageRefResolver resolver = new StorageImageRefResolver(new LocalDiskStorage(dir.toString(), ""));
        assertThat(resolver.resolve("https://img.example.com/pet.jpg")).isEqualTo("https://img.example.com/pet.jpg");
        assertThat(resolver.resolve("data:image/png;base64,AAAA")).isEqualTo("data:image/png;base64,AAAA");
    }

    /** 压缩：最长边缩到上限、重编码 JPEG，base64 后落在目标阈值内。 */
    @Test
    void compressorShrinksUnderTargetSize() throws IOException {
        byte[] png = photoLikePng(4000, 3000);
        ImageCompressor.Image out = ImageCompressor.compress(png, "image/png");

        assertThat(out.compressed()).isTrue();
        assertThat(out.mime()).isEqualTo("image/jpeg");
        assertThat(out.data().length).isLessThan(png.length);
        assertThat(out.base64Size()).isLessThan(ImageCompressor.DEFAULT_MAX_BASE64_BYTES);
    }

    /** 解不开的字节（非图片/损坏）安全回退：原样返回、标记未压缩、不抛异常。 */
    @Test
    void compressorFallsBackSafelyOnUndecodableBytes() {
        byte[] junk = "这不是一张图片".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ImageCompressor.Image out = ImageCompressor.compress(junk, "image/png");

        assertThat(out.compressed()).isFalse();
        assertThat(out.data()).isEqualTo(junk);
        assertThat(out.mime()).isEqualTo("image/png");
    }

    /** 造一张「像照片」的 PNG：渐变 + 圆斑，细节足够多，压缩前体积很大。 */
    private static byte[] photoLikePng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, new Color(220, 180, 140), w, h, new Color(40, 60, 90)));
        g.fillRect(0, 0, w, h);
        for (int i = 0; i < 400; i++) {
            g.setColor(new Color((i * 37) % 255, (i * 91) % 255, (i * 53) % 255, 140));
            int r = 8 + (i * 13) % Math.max(16, w / 8);
            g.fillOval((i * 97) % w, (i * 131) % h, r, r);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
