package com.github.lechandonga.statictiminganalyzerjava.sta.parse;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 逐行读取文本：去注释(//)、去空白；保留原始行号用于报错定位。 */
public final class LineReader {

    public record RawLine(int number, String text) {
    }

    private LineReader() {
    }

    public static List<RawLine> read(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new StaException("FILE_NOT_FOUND", file.toString(), 0, "文件不存在或不是普通文件");
        }
        List<RawLine> lines = new ArrayList<>();
        try {
            int n = 0;
            for (String raw : Files.readAllLines(file)) {
                n++;
                String s = raw;
                int hash = s.indexOf("//");
                if (hash >= 0) {
                    s = s.substring(0, hash);
                }
                s = s.trim();
                if (!s.isEmpty()) {
                    lines.add(new RawLine(n, s));
                }
            }
        } catch (IOException e) {
            throw new StaException("FILE_UNREADABLE", file.toString(), 0, e.getMessage());
        }
        return lines;
    }

    /** 按空白切分，并要求 token 数量落在区间内。 */
    public static String[] tokens(RawLine line, String source, int min, int max) {
        String[] t = line.text().split("\\s+");
        if (t.length < min || t.length > max) {
            throw new StaException("SYNTAX_ERROR", source, line.number(),
                    "字段数量非法，应为 " + min + (min == max ? "" : ".." + max) + " 个，实际 " + t.length);
        }
        return t;
    }

    /** 解析非负有限浮点，非法时抛出 VALUE_INVALID。 */
    public static double number(String s, String source, int line) {
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new StaException("VALUE_INVALID", source, line, "数值非法: " + s);
        }
        if (!Double.isFinite(v) || v < 0) {
            throw new StaException("VALUE_INVALID", source, line, "数值必须是非负有限数: " + s);
        }
        return v;
    }

    /** 解析正整数。 */
    public static int positiveInt(String s, String source, int line) {
        int v;
        try {
            v = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new StaException("VALUE_INVALID", source, line, "正整数非法: " + s);
        }
        if (v <= 0) {
            throw new StaException("VALUE_INVALID", source, line, "必须为正整数: " + s);
        }
        return v;
    }
}
