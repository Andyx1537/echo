package com.echo.infra.provenance;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 把 {@link AigcLabel} 写进文件元数据（{@code GB 45438—2025 §6.1} 文件元数据隐式标识）。
 *
 * <h2>为什么是改字节而不是重编码</h2>
 *
 * <p>用 ImageIO 解码再编码能顺带写元数据，但那会<b>重新压一遍图</b>——为了加一段标识把画质压掉一档，
 * 而且 PNG 这类无损格式会被悄悄换成另一套编码参数。这里改的是容器层：PNG 插一个
 * {@code tEXt} 块、JPEG 插一个 {@code COM} 段，<b>像素数据一个字节都不碰</b>。</p>
 *
 * <h2>只保留一份</h2>
 *
 * <p>{@code §6.1 c)}：一个文件里应仅保留一份文件元数据隐式标识。所以写之前先把已有的
 * {@code AIGC} 字段摘掉，而不是再追加一个——两份标识哪份算数，标准没规定，也不该由我们猜。</p>
 *
 * <h2>🔴 不支持的格式一律抛，不静默放行</h2>
 *
 * <p>标识义务附着于「生成」这个动作。一个生成出来却没能写上标识的文件，如果被静默放行，
 * 它与「已合规标识」的文件在系统里长得一模一样，事后无从区分。所以宁可让生成失败。</p>
 */
public final class AigcMetadataWriter {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] TEXT_CHUNK_TYPE = {'t', 'E', 'X', 't'};
    private static final int JPEG_MARKER_COM = 0xFE;
    private static final int JPEG_MARKER_SOS = 0xDA;
    private static final int JPEG_MARKER_EOI = 0xD9;

    private AigcMetadataWriter() {
    }

    /** 当前能写入隐式标识的媒体格式。 */
    public static boolean supports(byte[] data) {
        return isPng(data) || isJpeg(data);
    }

    /**
     * 写入隐式标识，返回新的字节。
     *
     * @throws UnsupportedMediaException 该格式尚不支持写入（🔴 调用方不得吞掉此异常当作已标识）
     */
    public static byte[] write(byte[] data, AigcLabel label) {
        String value = label.toMetadataValue();
        if (isPng(data)) {
            return writePng(data, value);
        }
        if (isJpeg(data)) {
            return writeJpeg(data, value);
        }
        throw new UnsupportedMediaException(
                "尚不支持为该格式写入 AIGC 文件元数据隐式标识（当前支持 PNG / JPEG）");
    }

    /**
     * 读回隐式标识的原始字符串；没有则返回 {@code null}。
     *
     * <p>写入侧的往返自检要用它，《标识办法》第十一条要求传播方<b>核验文件元数据中是否含有隐式标识</b>
     * 也要用它——两件事是同一个能力。</p>
     */
    public static String read(byte[] data) {
        if (isPng(data)) {
            return readPng(data);
        }
        if (isJpeg(data)) {
            return readJpeg(data);
        }
        return null;
    }

    /** 该格式暂时写不了隐式标识。 */
    public static final class UnsupportedMediaException extends RuntimeException {
        public UnsupportedMediaException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ PNG

    private static boolean isPng(byte[] d) {
        if (d == null || d.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (d[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** PNG：摘掉已有的 AIGC tEXt，然后在 IHDR 之后插一个新的。 */
    private static byte[] writePng(byte[] d, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length + value.length() + 64);
        out.write(d, 0, PNG_SIGNATURE.length);
        boolean inserted = false;
        int p = PNG_SIGNATURE.length;
        while (p + 8 <= d.length) {
            int len = readInt(d, p);
            if (len < 0 || p + 12 + len > d.length) {
                break; // 结构坏了：剩下的原样搬过去，不要在这里把文件毁掉
            }
            String type = new String(d, p + 4, 4, StandardCharsets.US_ASCII);
            int chunkTotal = 12 + len;
            if (!(isTextChunk(type) && isAigcText(d, p + 8, len))) {
                out.write(d, p, chunkTotal);
            }
            if ("IHDR".equals(type) && !inserted) {
                writeChunk(out, TEXT_CHUNK_TYPE, textChunkData(value));
                inserted = true;
            }
            p += chunkTotal;
        }
        if (p < d.length) {
            out.write(d, p, d.length - p);
        }
        if (!inserted) {
            throw new UnsupportedMediaException("PNG 里找不到 IHDR，无法安放隐式标识");
        }
        return out.toByteArray();
    }

    private static String readPng(byte[] d) {
        int p = PNG_SIGNATURE.length;
        while (p + 8 <= d.length) {
            int len = readInt(d, p);
            if (len < 0 || p + 12 + len > d.length) {
                return null;
            }
            String type = new String(d, p + 4, 4, StandardCharsets.US_ASCII);
            if (isTextChunk(type) && isAigcText(d, p + 8, len)) {
                String raw = new String(d, p + 8, len, StandardCharsets.ISO_8859_1);
                int nul = raw.indexOf('\0');
                return nul < 0 ? null : raw.substring(nul + 1);
            }
            p += 12 + len;
        }
        return null;
    }

    private static boolean isTextChunk(String type) {
        return "tEXt".equals(type) || "iTXt".equals(type);
    }

    /** tEXt 数据的开头是「关键词 + 0x00」，判断关键词是不是 AIGC。 */
    private static boolean isAigcText(byte[] d, int from, int len) {
        byte[] kw = AigcLabel.FIELD_KEYWORD.getBytes(StandardCharsets.US_ASCII);
        if (len < kw.length + 1) {
            return false;
        }
        for (int i = 0; i < kw.length; i++) {
            if (d[from + i] != kw[i]) {
                return false;
            }
        }
        return d[from + kw.length] == 0;
    }

    private static byte[] textChunkData(String value) {
        byte[] kw = AigcLabel.FIELD_KEYWORD.getBytes(StandardCharsets.US_ASCII);
        // 值已按附录 E j) 收敛为单字节可打印字符，Latin-1 与 ASCII 在此范围内一致
        byte[] text = value.getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = new byte[kw.length + 1 + text.length];
        System.arraycopy(kw, 0, data, 0, kw.length);
        data[kw.length] = 0;
        System.arraycopy(text, 0, data, kw.length + 1, text.length);
        return data;
    }

    private static void writeChunk(ByteArrayOutputStream out, byte[] type, byte[] data) {
        writeInt(out, data.length);
        out.write(type, 0, type.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static int readInt(byte[] d, int p) {
        return ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    // ----------------------------------------------------------------- JPEG

    private static boolean isJpeg(byte[] d) {
        return d != null && d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
    }

    /** JPEG：摘掉带 AIGC 的 COM 段，然后紧跟 SOI 插一个新的。 */
    private static byte[] writeJpeg(byte[] d, String value) {
        byte[] payload = value.getBytes(StandardCharsets.ISO_8859_1);
        if (payload.length + 2 > 0xFFFF) {
            throw new UnsupportedMediaException("隐式标识过长，装不进一个 JPEG COM 段");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(d.length + payload.length + 8);
        out.write(0xFF);
        out.write(0xD8);
        out.write(0xFF);
        out.write(JPEG_MARKER_COM);
        int segLen = payload.length + 2;
        out.write((segLen >>> 8) & 0xFF);
        out.write(segLen & 0xFF);
        out.write(payload, 0, payload.length);

        int p = 2;
        while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
            int marker = d[p + 1] & 0xFF;
            if (marker == JPEG_MARKER_SOS || marker == JPEG_MARKER_EOI) {
                break; // SOS 之后是熵编码数据，不再是段结构
            }
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > d.length) {
                break;
            }
            boolean dropExisting = marker == JPEG_MARKER_COM && containsKeyword(d, p + 4, len - 2);
            if (!dropExisting) {
                out.write(d, p, 2 + len);
            }
            p += 2 + len;
        }
        if (p < d.length) {
            out.write(d, p, d.length - p);
        }
        return out.toByteArray();
    }

    private static String readJpeg(byte[] d) {
        int p = 2;
        while (p + 4 <= d.length && (d[p] & 0xFF) == 0xFF) {
            int marker = d[p + 1] & 0xFF;
            if (marker == JPEG_MARKER_SOS || marker == JPEG_MARKER_EOI) {
                return null;
            }
            int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
            if (len < 2 || p + 2 + len > d.length) {
                return null;
            }
            if (marker == JPEG_MARKER_COM && containsKeyword(d, p + 4, len - 2)) {
                return new String(d, p + 4, len - 2, StandardCharsets.ISO_8859_1);
            }
            p += 2 + len;
        }
        return null;
    }

    private static boolean containsKeyword(byte[] d, int from, int len) {
        if (len <= 0) {
            return false;
        }
        return new String(d, from, len, StandardCharsets.ISO_8859_1)
                .contains(AigcLabel.FIELD_KEYWORD);
    }
}
