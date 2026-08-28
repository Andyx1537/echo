package com.echo.http;

import java.nio.charset.StandardCharsets;

/**
 * 极简 multipart/form-data 解析器（二进制安全），只取第一个带 filename 的文件分段。
 *
 * <p>JDK 内置 {@code HttpServer} 不解析 multipart，前端 {@code FormData} 上传单文件（字段 file）
 * 走这里。逐字节按 boundary 切分，不把二进制当字符串处理，避免损坏图片/音视频。</p>
 *
 * <p>够用即可：单文件、忽略其他表单字段。若未来需要多文件/多字段，再扩展为返回分段列表。</p>
 */
public final class MultipartParser {

    private MultipartParser() {
    }

    /** 解析结果：一个文件分段。 */
    public record FilePart(String fieldName, String filename, String contentType, byte[] data) {
    }

    /**
     * @param body        请求原始字节
     * @param contentType 请求头 Content-Type（含 boundary）
     * @return 第一个文件分段；无则返回 null
     */
    public static FilePart parseFirstFile(byte[] body, String contentType) {
        String boundary = extractBoundary(contentType);
        if (boundary == null || body == null || body.length == 0) {
            return null;
        }
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlfcrlf = {13, 10, 13, 10};

        int pos = 0;
        while (pos < body.length) {
            int start = indexOf(body, delim, pos);
            if (start < 0) {
                break;
            }
            int partStart = start + delim.length;
            // 结束边界 "--boundary--"
            if (partStart + 2 <= body.length && body[partStart] == '-' && body[partStart + 1] == '-') {
                break;
            }
            // 跳过分隔符后的 CRLF
            if (partStart + 2 <= body.length && body[partStart] == 13 && body[partStart + 1] == 10) {
                partStart += 2;
            }
            int nextDelim = indexOf(body, delim, partStart);
            if (nextDelim < 0) {
                break;
            }
            int headerEnd = indexOf(body, crlfcrlf, partStart);
            if (headerEnd < 0 || headerEnd > nextDelim) {
                pos = nextDelim;
                continue;
            }
            String headers = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
            int contentStart = headerEnd + crlfcrlf.length;
            // 内容结尾去掉分隔符前的 CRLF
            int contentEnd = nextDelim;
            if (contentEnd - 2 >= contentStart && body[contentEnd - 2] == 13 && body[contentEnd - 1] == 10) {
                contentEnd -= 2;
            }
            String filename = headerParam(headers, "filename");
            if (filename != null) {
                String name = headerParam(headers, "name");
                String partCt = headerValue(headers, "Content-Type");
                byte[] data = new byte[Math.max(0, contentEnd - contentStart)];
                System.arraycopy(body, contentStart, data, 0, data.length);
                return new FilePart(name, decodeFilename(filename),
                        partCt == null ? "application/octet-stream" : partCt.trim(), data);
            }
            pos = nextDelim;
        }
        return null;
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase();
        int i = lower.indexOf("boundary=");
        if (i < 0) {
            return null;
        }
        String b = contentType.substring(i + "boundary=".length()).trim();
        if (b.startsWith("\"")) {
            int end = b.indexOf('"', 1);
            return end > 0 ? b.substring(1, end) : b.substring(1);
        }
        int semi = b.indexOf(';');
        return semi >= 0 ? b.substring(0, semi).trim() : b;
    }

    /** 从形如 {@code form-data; name="file"; filename="a.png"} 中取某参数值。 */
    private static String headerParam(String headers, String param) {
        String key = param + "=\"";
        int i = headers.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = headers.indexOf('"', start);
        return end > 0 ? headers.substring(start, end) : null;
    }

    /** 取某 header 行的值（如 Content-Type）。 */
    private static String headerValue(String headers, String name) {
        for (String line : headers.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String decodeFilename(String raw) {
        // 浏览器一般发 UTF-8 文件名；ISO_8859_1 读入后按 UTF-8 还原
        byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
