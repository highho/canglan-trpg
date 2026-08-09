package com.canglan.data.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JSON 配置文件读取工具：UTF-8 + 缺失文件即抛（数据文件不许静默缺失）。
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** 读取 UTF-8 文本；文件不存在直接抛异常（对应 C# File.ReadAllText 语义）。 */
    public static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取配置文件失败: " + path, e);
        }
    }

    public static String readText(String path) {
        return readText(Path.of(path));
    }
}
