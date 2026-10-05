package com.github.lechandonga.statictiminganalyzerjava.sta;

/**
 * Static timing analysis failure.
 *
 * <p>The {@link #code} is a stable, machine-readable error category so that callers can
 * distinguish duplicate definitions, dangling references, illegal values, combinational
 * loops and so on; {@link #getMessage()} always carries the human-readable location
 * detail (file, line, object name).
 */
public class StaException extends RuntimeException {

    public enum Code {
        PARSE_ERROR,
        DUPLICATE_DEFINITION,
        UNKNOWN_REFERENCE,
        ILLEGAL_VALUE,
        ILLEGAL_UNIT,
        FORMAT_ERROR,
        COMBINATIONAL_LOOP,
        CONSTRAINT_CONFLICT
    }

    private final Code code;

    public StaException(Code code, String message) {
        super(code.name() + ": " + message);
        this.code = code;
    }

    public StaException(Code code, String message, Throwable cause) {
        super(code.name() + ": " + message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
