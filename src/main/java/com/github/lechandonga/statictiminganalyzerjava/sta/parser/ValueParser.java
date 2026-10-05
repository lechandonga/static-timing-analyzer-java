package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;

import java.util.Locale;

/** Strict numeric parsing with explicit, distinguishable failures. */
final class ValueParser {

    private ValueParser() {
    }

    static double nonNegativeNumber(String fileName, LineReader.Line line, String token,
                                    String what) {
        double value = number(fileName, line, token, what);
        if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
            throw LineReader.fail(fileName, line,
                    what + " must be a finite non-negative number, got '" + token + "'");
        }
        return value;
    }

    static double number(String fileName, LineReader.Line line, String token, String what) {
        try {
            double value = Double.parseDouble(token.toLowerCase(Locale.ROOT));
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new StaException(StaException.Code.ILLEGAL_VALUE,
                    fileName + ":" + line.number() + ": " + what
                            + " must be a number, got '" + token + "'");
        }
    }

    static int positiveInt(String fileName, LineReader.Line line, String token, String what) {
        double value = number(fileName, line, token, what);
        if (value != Math.floor(value) || value < 1) {
            throw new StaException(StaException.Code.ILLEGAL_VALUE,
                    fileName + ":" + line.number() + ": " + what
                            + " must be a positive integer, got '" + token + "'");
        }
        return (int) value;
    }
}
