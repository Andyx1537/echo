package com.aengine.util;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;

/**
 * protobuf 序列化工具。
 *
 * <p>替代已废弃的 {@code com.googlecode.protobuf-java-format}，统一使用
 * protobuf 官方 {@code protobuf-java-util} 提供的 {@link JsonFormat}，
 * 并将受检异常包装为运行时异常，保持原有调用方法签名不变。</p>
 */
public final class ProtobufUtil {

    private static final JsonFormat.Printer JSON_PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    private static final JsonFormat.Parser JSON_PARSER = JsonFormat.parser().ignoringUnknownFields();

    private ProtobufUtil() {
    }

    /**
     * 将 protobuf 消息序列化为 JSON 字符串。
     */
    public static String toJson(MessageOrBuilder message) {
        try {
            return JSON_PRINTER.print(message);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("protobuf to json failed", e);
        }
    }

    /**
     * 将 JSON 字符串解析合并到 protobuf builder。
     */
    public static void mergeJson(String json, Message.Builder builder) {
        try {
            JSON_PARSER.merge(json, builder);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("json to protobuf failed", e);
        }
    }

    /**
     * 调试用：输出 protobuf 消息的文本格式（等价旧 TextFormat.printToUnicodeString）。
     */
    public static String toText(MessageOrBuilder message) {
        return TextFormat.printer().printToString(message);
    }
}
