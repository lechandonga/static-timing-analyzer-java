package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Cell;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Library;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimingArc;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimeUnit;

import java.util.List;

/**
 * Parses the local cell library format:
 * <pre>
 * time_unit ns
 * cell INV {
 *     arc A -> Z comb delay 0.05 0.06
 * }
 * cell DFF {
 *     arc CLK -> Q c2q delay 0.10 0.12
 *     check D vs CLK setup 0.04
 *     check D vs CLK hold 0.02
 * }
 * </pre>
 */
public final class LibraryParser {

    private LibraryParser() {
    }

    public static Library parse(String fileName, String content) {
        List<LineReader.Line> lines = LineReader.read(fileName, content);
        Library library = new Library();
        TimeUnit declaredUnit = null;
        for (LineReader.Line line : lines) {
            String[] tokens = line.text().split("\\s+");
            if (tokens.length == 2 && tokens[0].equals("time_unit")) {
                if (declaredUnit != null) {
                    throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                            fileName + ":" + line.number()
                                    + ": time_unit is declared more than once");
                }
                try {
                    declaredUnit = TimeUnit.parse(tokens[1]);
                } catch (IllegalArgumentException e) {
                    throw new StaException(StaException.Code.ILLEGAL_UNIT,
                            fileName + ":" + line.number() + ": " + e.getMessage());
                }
            }
        }
        if (declaredUnit != null) {
            library.setTimeUnit(declaredUnit);
        }
        Cell current = null;

        for (LineReader.Line line : lines) {
            String text = line.text();
            if ("}".equals(text)) {
                if (current == null) {
                    throw LineReader.fail(fileName, line, "unmatched '}'");
                }
                current = null;
                continue;
            }
            if (current == null) {
                String[] tokens = text.split("\\s+");
                if (tokens.length == 2 && tokens[0].equals("time_unit")) {
                    continue;
                }
                if (tokens.length == 3 && tokens[0].equals("cell") && tokens[2].equals("{")) {
                    String name = tokens[1];
                    if (library.getCells().containsKey(name)) {
                        throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                                fileName + ":" + line.number()
                                        + ": cell '" + name + "' is defined more than once");
                    }
                    current = new Cell(name);
                    library.getCells().put(name, current);
                    continue;
                }
                throw LineReader.fail(fileName, line,
                        "expected 'time_unit <ps|ns>' or 'cell <name> {'");
            }

            parseCellBody(fileName, line, text, current, library.getTimeUnit());
        }
        if (current != null) {
            throw new StaException(StaException.Code.FORMAT_ERROR,
                    fileName + ": cell '" + current.getName() + "' is missing its closing '}'");
        }
        if (library.getCells().isEmpty()) {
            throw new StaException(StaException.Code.FORMAT_ERROR,
                    fileName + ": library defines no cells");
        }
        return library;
    }

    private static void parseCellBody(String fileName, LineReader.Line line, String text,
                                      Cell cell, TimeUnit unit) {
        String[] tokens = text.split("\\s+");
        // arc <from> -> <to> <comb|c2q> delay <rise> <fall>
        if (tokens.length == 8 && tokens[0].equals("arc") && tokens[2].equals("->")
                && tokens[5].equals("delay")) {
            TimingArc.ArcType type = switch (tokens[4]) {
                case "comb" -> TimingArc.ArcType.COMBINATIONAL;
                case "c2q" -> TimingArc.ArcType.CLOCK_TO_Q;
                default -> throw LineReader.fail(fileName, line,
                        "arc type must be 'comb' or 'c2q', got '" + tokens[4] + "'");
            };
            double rise = ValueParser.nonNegativeNumber(fileName, line, tokens[6], "rise delay");
            double fall = ValueParser.nonNegativeNumber(fileName, line, tokens[7], "fall delay");
            rejectDuplicateArc(fileName, line, cell, tokens[1], tokens[3], type);
            cell.addArc(new TimingArc(tokens[1], tokens[3], type,
                    unit.toPs(rise), unit.toPs(fall)));
            return;
        }
        // check <dataPin> vs <clockPin> <setup|hold> <value>
        if (tokens.length == 6 && tokens[0].equals("check") && tokens[2].equals("vs")) {
            TimingArc.ArcType type = switch (tokens[4]) {
                case "setup" -> TimingArc.ArcType.SETUP;
                case "hold" -> TimingArc.ArcType.HOLD;
                default -> throw LineReader.fail(fileName, line,
                        "check kind must be 'setup' or 'hold', got '" + tokens[4] + "'");
            };
            double value = ValueParser.nonNegativeNumber(fileName, line, tokens[5],
                    tokens[4] + " constraint");
            rejectDuplicateArc(fileName, line, cell, tokens[3], tokens[1], type);
            cell.addArc(new TimingArc(tokens[3], tokens[1], type,
                    unit.toPs(value), unit.toPs(value)));
            return;
        }
        throw LineReader.fail(fileName, line,
                "expected 'arc <from> -> <to> <comb|c2q> delay <rise> <fall>' "
                        + "or 'check <data> vs <clk> <setup|hold> <value>'");
    }

    private static void rejectDuplicateArc(String fileName, LineReader.Line line, Cell cell,
                                           String from, String to, TimingArc.ArcType type) {
        if (cell.findArc(from, to, type) != null) {
            throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                    fileName + ":" + line.number() + ": duplicate " + type
                            + " arc '" + from + " -> " + to + "' in cell '" + cell.getName()
                            + "'");
        }
    }
}
