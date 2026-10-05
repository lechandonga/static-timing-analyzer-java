package com.github.lechandonga.statictiminganalyzerjava.sta;

/**
 * 输入文件不合法时抛出。code 取值见 docs/input-format.md 的“错误码”章节，
 * 保证不同失败原因可程序化区分、不会被静默忽略。
 */
public class StaException extends RuntimeException {

    private final String code;
    private final String source;
    private final int line;

    public StaException(String code, String source, int line, String message) {
        super("[" + code + "] " + source + ":" + line + " " + message);
        this.code = code;
        this.source = source;
        this.line = line;
    }

    public String getCode() {
        return code;
    }

    public String getSource() {
        return source;
    }

    public int getLine() {
        return line;
    }
}
