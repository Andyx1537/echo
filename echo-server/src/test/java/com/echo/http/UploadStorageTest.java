package com.echo.http;

import com.echo.infra.storage.IStorage;
import com.echo.infra.storage.LocalDiskStorage;
import com.echo.infra.storage.StorageConfig;
import com.echo.infra.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * multipart 解析（二进制安全）与本地磁盘存储往返单测。不依赖网络/DB。
 */
class UploadStorageTest {

    /** 含 CRLF、0x00、0xFF 的二进制内容也能原样解析（不被当字符串损坏）。 */
    @Test
    void parsesBinaryMultipartExactly() throws IOException {
        byte[] fileBytes = {0x00, 0x0D, 0x0A, (byte) 0xFF, (byte) 0x89, 'P', 'N', 'G', 0x1A, 0x00, 0x42};
        String boundary = "----EchoBoundary123";
        byte[] body = buildMultipart(boundary, "file", "麦麦.png", "image/png", fileBytes);

        MultipartParser.FilePart part = MultipartParser.parseFirstFile(
                body, "multipart/form-data; boundary=" + boundary);

        assertThat(part).isNotNull();
        assertThat(part.fieldName()).isEqualTo("file");
        assertThat(part.filename()).isEqualTo("麦麦.png");
        assertThat(part.contentType()).isEqualTo("image/png");
        assertThat(part.data()).isEqualTo(fileBytes);
    }

    @Test
    void returnsNullWhenNoBoundaryOrNoFile() {
        assertThat(MultipartParser.parseFirstFile(new byte[]{1, 2, 3}, "application/json")).isNull();
        assertThat(MultipartParser.parseFirstFile(new byte[0], "multipart/form-data; boundary=x")).isNull();
    }

    @Test
    void localStorageRoundTrip(@TempDir Path dir) {
        IStorage storage = new LocalDiskStorage(dir.toString(), "");
        byte[] data = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x00, 0x42};
        IStorage.Stored stored = storage.put("1234567890", data, "image/png", "麦麦.png");

        assertThat(stored.resourceId()).isEqualTo("1234567890");
        assertThat(stored.key()).isEqualTo("1234567890.png");
        assertThat(stored.url()).isEqualTo("/api/v1/files/1234567890.png");

        IStorage.Loaded loaded = storage.load(stored.key());
        assertThat(loaded).isNotNull();
        assertThat(loaded.data()).isEqualTo(data);
        assertThat(loaded.contentType()).isEqualTo("image/png");

        assertThat(storage.load("nope.png")).isNull();
        // 目录穿越防护：非法 key 读不到
        assertThat(storage.load("../secret")).isNull();
    }

    @Test
    void baseUrlPrefixWhenCrossOrigin(@TempDir Path dir) {
        IStorage storage = new LocalDiskStorage(dir.toString(), "http://127.0.0.1:8080");
        IStorage.Stored stored = storage.put("9", new byte[]{1}, "image/jpeg", null);
        assertThat(stored.key()).isEqualTo("9.jpg");
        assertThat(stored.url()).isEqualTo("http://127.0.0.1:8080/api/v1/files/9.jpg");
    }

    @Test
    void factoryDefaultsLocalAndRejectsUnimplementedCloud(@TempDir Path dir) {
        Map<String, String> env = Map.of("ECHO_STORAGE_TYPE", "local", "ECHO_STORAGE_DIR", dir.toString());
        assertThat(StorageFactory.create(StorageConfig.from(env::get))).isInstanceOf(LocalDiskStorage.class);

        Map<String, String> oss = Map.of("ECHO_STORAGE_TYPE", "oss", "ECHO_STORAGE_DIR", dir.toString());
        assertThatThrownBy(() -> StorageFactory.create(StorageConfig.from(oss::get)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未接入");
    }

    private static byte[] buildMultipart(String boundary, String field, String filename,
                                         String contentType, byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
