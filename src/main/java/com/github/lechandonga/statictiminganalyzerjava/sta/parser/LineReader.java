package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/** Splits an input file into meaningful, non-blank lines with 1-based line numbers. */
final class LineReader {

    record Line(int number, String text) {
    }

    private LineReader() {
    }

    static List<Line> read(String fileName, String content) {
        List<Line> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String raw;
            int number = 0;
            while ((raw = reader.readLine()) != null) {
                number++;
                int hash = raw.indexOf('#');
                if (hash >= 0) {
                    raw = raw.substring(0, hash);
                }
                String trimmed = raw.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(new Line(number, trimmed));
                }
            }
        } catch (IOException e) {
            throw new StaException(StaException.Code.PARSE_ERROR,
                    "failed to read " + fileName + ": " + e.getMessage(), e);
        }
        return lines;
    }

    static StaException fail(String fileName, Line line, String message) {
        return new StaException(StaException.Code.FORMAT_ERROR,
                fileName + ":" + line.number() + ": " + message + " (line: \"" + line.text() + "\")");
    }
}
