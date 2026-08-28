package com.echo.infra.provenance;

import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.LocalDiskStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 把隐式标识写进文件元数据的单测（{@code GB 45438—2025 §6.1}）。
 *
 * <p>三件事必须成立：<b>写得进去</b>（读得回来、逐字相同）、<b>不毁图</b>（像素一个不差）、
 * <b>只留一份</b>（§6.1 c)）。第二件尤其要钉——为了加一段标识把图重压一遍，是最容易被默默接受的代价。</p>
 *
 * <p>不发起任何网络调用。</p>
 */
class AigcMetadataWriterTest {

    private static final String PRODUCER = "00ECHO000000000000000010001";

    private static AigcLabel label(String produceId) {
        return AigcLabel.firstWrite(PRODUCER, produceId);
    }

    private static byte[] image(String format) throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(180, 120, 90));
        g.fillRect(0, 0, 64, 48);
        g.setColor(new Color(30, 60, 110));
        g.fillOval(8, 8, 32, 24);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    private static void assertPixelsIdentical(byte[] before, byte[] after) throws IOException {
        BufferedImage a = ImageIO.read(new ByteArrayInputStream(before));
        BufferedImage b = ImageIO.read(new ByteArrayInputStream(after));
        assertThat(b).isNotNull();
        assertThat(b.getWidth()).isEqualTo(a.getWidth());
        assertThat(b.getHeight()).isEqualTo(a.getHeight());
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                assertThat(b.getRGB(x, y))
                        .as("加标识不该动像素 (%d,%d)", x, y)
                        .isEqualTo(a.getRGB(x, y));
            }
        }
    }

    // ------------------------------------------------------------------ PNG

    @Test
    void pngRoundTripsTheLabelVerbatim() throws IOException {
        byte[] png = image("png");
        AigcLabel l = label("8823393053208561601");

        byte[] out = AigcMetadataWriter.write(png, l);

        assertThat(AigcMetadataWriter.read(out)).isEqualTo(l.toMetadataValue());
        assertThat(new String(out, StandardCharsets.ISO_8859_1)).contains("AIGC");
    }

    @Test
    void pngPixelsSurviveLabelling() throws IOException {
        byte[] png = image("png");
        assertPixelsIdentical(png, AigcMetadataWriter.write(png, label("1")));
    }

    /** 🔴 §6.1 c)：一个文件里应仅保留一份隐式标识。重复写入是覆盖，不是追加。 */
    @Test
    void pngKeepsExactlyOneLabelAfterRewriting() throws IOException {
        byte[] once = AigcMetadataWriter.write(image("png"), label("first"));
        byte[] twice = AigcMetadataWriter.write(once, label("second"));

        assertThat(AigcMetadataWriter.read(twice)).isEqualTo(label("second").toMetadataValue());
        assertThat(countOccurrences(twice, "\"AIGC\": {")).isEqualTo(1);
        assertThat(new String(twice, StandardCharsets.ISO_8859_1)).doesNotContain("\"first\"");
    }

    @Test
    void unlabelledPngReadsBackAsNull() throws IOException {
        assertThat(AigcMetadataWriter.read(image("png"))).isNull();
    }

    // ----------------------------------------------------------------- JPEG

    @Test
    void jpegRoundTripsTheLabelVerbatim() throws IOException {
        byte[] jpg = image("jpg");
        AigcLabel l = label("8823393053208561601");

        byte[] out = AigcMetadataWriter.write(jpg, l);

        assertThat(AigcMetadataWriter.read(out)).isEqualTo(l.toMetadataValue());
    }

    @Test
    void jpegPixelsSurviveLabelling() throws IOException {
        byte[] jpg = image("jpg");
        // JPEG 本身有损，但加标识这一步不重编码，所以解出来应当逐像素相同
        assertPixelsIdentical(jpg, AigcMetadataWriter.write(jpg, label("1")));
    }

    @Test
    void jpegKeepsExactlyOneLabelAfterRewriting() throws IOException {
        byte[] once = AigcMetadataWriter.write(image("jpg"), label("first"));
        byte[] twice = AigcMetadataWriter.write(once, label("second"));

        assertThat(AigcMetadataWriter.read(twice)).isEqualTo(label("second").toMetadataValue());
        assertThat(countOccurrences(twice, "\"AIGC\": {")).isEqualTo(1);
    }

    @Test
    void unlabelledJpegReadsBackAsNull() throws IOException {
        assertThat(AigcMetadataWriter.read(image("jpg"))).isNull();
    }

    // ------------------------------------------------------------ 不支持的格式

    /**
     * 🔴 写不了标识就抛，<b>不静默放行</b>。
     *
     * <p>放行的话，一个生成出来却没标识的文件，与已合规标识的文件在系统里长得一模一样，事后无从区分。</p>
     */
    @Test
    void unsupportedFormatsFailLoudlyInsteadOfPassingThrough() {
        byte[] mp3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> AigcMetadataWriter.write(mp3, label("1")))
                .isInstanceOf(AigcMetadataWriter.UnsupportedMediaException.class);
        assertThat(AigcMetadataWriter.supports(mp3)).isFalse();
    }

    @Test
    void supportsReportsPngAndJpeg() throws IOException {
        assertThat(AigcMetadataWriter.supports(image("png"))).isTrue();
        assertThat(AigcMetadataWriter.supports(image("jpg"))).isTrue();
        assertThat(AigcMetadataWriter.supports(new byte[0])).isFalse();
    }

    // -------------------------------------------------------------- 落盘入口

    /** 🔴 编码没配好就不许落盘——宁可生成失败，也不落一个没有标识的文件。 */
    @Test
    void publisherRefusesToStoreWhenTheProviderCodeIsMissing(@TempDir Path dir) throws IOException {
        IStorage storage = new LocalDiskStorage(dir.toString(), "");
        GeneratedMediaPublisher publisher =
                new GeneratedMediaPublisher(storage, ProvenanceConfig.from(k -> null));

        byte[] png = image("png");
        assertThatThrownBy(() -> publisher.publish("1", png, "image/png", "p.png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不得由代码自行编造");
    }

    @Test
    void publisherStoresBytesThatCarryTheLabel(@TempDir Path dir) throws IOException {
        IStorage storage = new LocalDiskStorage(dir.toString(), "");
        GeneratedMediaPublisher publisher =
                new GeneratedMediaPublisher(storage, ProvenanceConfig.from(k -> PRODUCER));

        IStorage.Stored stored = publisher.publish("8823393053208561601", image("png"), "image/png", "p.png");

        IStorage.Loaded back = storage.load(stored.key());
        assertThat(back).isNotNull();
        String value = AigcMetadataWriter.read(back.data());
        assertThat(value).isEqualTo(
                AigcLabel.firstWrite(PRODUCER, "8823393053208561601").toMetadataValue());
    }

    private static int countOccurrences(byte[] data, String needle) {
        String s = new String(data, StandardCharsets.ISO_8859_1);
        int n = 0;
        int i = s.indexOf(needle);
        while (i >= 0) {
            n++;
            i = s.indexOf(needle, i + 1);
        }
        return n;
    }
}
