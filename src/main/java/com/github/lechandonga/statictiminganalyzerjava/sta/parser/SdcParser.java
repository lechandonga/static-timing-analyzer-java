package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.Clock;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.ExceptionKind;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.ExceptionRule;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimeUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the local timing constraint format:
 * <pre>
 * time_unit ns
 * create_clock CLK period 10.0 uncertainty 0.05 sources CLK u2/CLK
 * set_input_delay 0.8 clock CLK A
 * set_output_delay 0.5 clock CLK Y
 * set_false_path from A through u1/Z to u2/D
 * set_multicycle_path 2 setup from ff1/Q to ff2/D
 * set_multicycle_path 1 hold from ff1/Q to ff2/D
 * </pre>
 */
public final class SdcParser {

    private SdcParser() {
    }

    public static Constraints parse(String fileName, String content) {
        List<LineReader.Line> lines = LineReader.read(fileName, content);
        Constraints constraints = new Constraints();
        TimeUnit declaredUnit = null;
        for (LineReader.Line line : lines) {
            String[] tokens = line.text().split("\\s+");
            if (tokens.length == 2 && tokens[0].equals("time_unit")) {
                try {
                    TimeUnit parsed = TimeUnit.parse(tokens[1]);
                    if (declaredUnit != null && parsed != declaredUnit) {
                        throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                                fileName + ":" + line.number()
                                        + ": time_unit is declared more than once");
                    }
                    declaredUnit = parsed;
                } catch (IllegalArgumentException e) {
                    throw new StaException(StaException.Code.ILLEGAL_UNIT,
                            fileName + ":" + line.number() + ": " + e.getMessage());
                }
            }
        }
        if (declaredUnit != null) {
            constraints.setTimeUnit(declaredUnit);
        }

        for (LineReader.Line line : lines) {
            String[] tokens = line.text().split("\\s+");
            switch (tokens[0]) {
                case "time_unit" -> {
                    if (tokens.length != 2) {
                        throw LineReader.fail(fileName, line, "expected 'time_unit <ps|ns>'");
                    }
                    // handled in the pre-pass so unit placement cannot change results
                }
                case "create_clock" -> parseClock(fileName, line, tokens, constraints);
                case "set_input_delay", "set_output_delay" ->
                        parsePortDelay(fileName, line, tokens, constraints);
                case "set_false_path" -> parseFalsePath(fileName, line, tokens, constraints);
                case "set_multicycle_path" ->
                        parseMulticycle(fileName, line, tokens, constraints);
                default -> throw LineReader.fail(fileName, line,
                        "unknown constraint statement '" + tokens[0] + "'");
            }
        }
        return constraints;
    }

    private static void parseClock(String fileName, LineReader.Line line, String[] tokens,
                                   Constraints constraints) {
        if (tokens.length < 4 || !tokens[2].equals("period")) {
            throw LineReader.fail(fileName, line,
                    "expected 'create_clock <name> period <value> [uncertainty <value>] "
                            + "sources <pin...>'");
        }
        String name = tokens[1];
        double periodValue;
        try {
            periodValue = ValueParser.number(fileName, line, tokens[3], "clock period");
        } catch (StaException e) {
            throw e;
        }
        if (!Double.isFinite(periodValue) || periodValue <= 0) {
            throw new StaException(StaException.Code.ILLEGAL_VALUE,
                    fileName + ":" + line.number()
                            + ": clock period must be a finite positive number, got '"
                            + tokens[3] + "'");
        }
        double periodPs = constraints.getTimeUnit().toPs(periodValue);
        double uncertaintyPs = 0.0;
        int index = 4;
        if (index < tokens.length && tokens[index].equals("uncertainty")) {
            if (index + 1 >= tokens.length) {
                throw LineReader.fail(fileName, line, "uncertainty requires a value");
            }
            uncertaintyPs = constraints.getTimeUnit().toPs(ValueParser.nonNegativeNumber(
                    fileName, line, tokens[index + 1], "clock uncertainty"));
            index += 2;
        }
        if (index >= tokens.length || !tokens[index].equals("sources")
                || index + 1 >= tokens.length) {
            throw LineReader.fail(fileName, line,
                    "clock '" + name + "' must declare at least one 'sources <ref>'");
        }
        List<String> sources = new ArrayList<>();
        for (int i = index + 1; i < tokens.length; i++) {
            String source = PinReferences.parse(fileName, line, tokens[i]).canonical();
            if (sources.contains(source)) {
                throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                        fileName + ":" + line.number()
                                + ": clock '" + name + "' lists source '" + source
                                + "' more than once");
            }
            sources.add(source);
        }
        if (uncertaintyPs >= periodPs) {
            throw new StaException(StaException.Code.ILLEGAL_VALUE,
                    fileName + ":" + line.number()
                            + ": clock '" + name + "' uncertainty must be smaller than period");
        }
        constraints.addClock(new Clock(name, periodPs, uncertaintyPs, sources));
    }

    private static void parsePortDelay(String fileName, LineReader.Line line, String[] tokens,
                                       Constraints constraints) {
        if (tokens.length != 5 || !tokens[2].equals("clock")) {
            throw LineReader.fail(fileName, line,
                    "expected '" + tokens[0] + " <delay> clock <clock> <port>'");
        }
        double delay = constraints.getTimeUnit().toPs(
                ValueParser.nonNegativeNumber(fileName, line, tokens[1], "port delay"));
        String clockName = tokens[3];
        String port = tokens[4];
        if (port.indexOf('/') >= 0) {
            throw LineReader.fail(fileName, line, "port delay target must be a top-level port");
        }
        var targetMap = tokens[0].equals("set_input_delay")
                ? constraints.getInputDelaysPs() : constraints.getOutputDelaysPs();
        if (targetMap.containsKey(port)) {
            throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                    fileName + ":" + line.number() + ": " + tokens[0]
                            + " for port '" + port + "' is declared more than once");
        }
        targetMap.put(port, delay);
        // clock name reference is validated once netlist/library are linked
        constraints.getPortDelayClock().put(tokens[0] + ":" + port, clockName);
    }

    private static void parseFalsePath(String fileName, LineReader.Line line, String[] tokens,
                                       Constraints constraints) {
        PathSpec spec = PathSpec.parse(fileName, line, tokens, 1);
        constraints.addException(new ExceptionRule(line.number(), ExceptionKind.FALSE_PATH,
                spec.from, spec.through, spec.to, null, null));
    }

    private static void parseMulticycle(String fileName, LineReader.Line line, String[] tokens,
                                        Constraints constraints) {
        if (tokens.length < 3) {
            throw LineReader.fail(fileName, line,
                    "expected 'set_multicycle_path <n> <setup|hold> [from ...] [through ...] [to ...]'");
        }
        int cycles = ValueParser.positiveInt(fileName, line, tokens[1], "multicycle count");
        boolean setup = switch (tokens[2]) {
            case "setup" -> true;
            case "hold" -> false;
            default -> throw LineReader.fail(fileName, line,
                    "multicycle mode must be 'setup' or 'hold', got '" + tokens[2] + "'");
        };
        PathSpec spec = PathSpec.parse(fileName, line, tokens, 3);
        constraints.addException(new ExceptionRule(line.number(),
                ExceptionKind.MULTICYCLE_PATH, spec.from, spec.through, spec.to,
                setup ? cycles : null, setup ? null : cycles));
    }

    private static final class PathSpec {
        private String from;
        private List<String> through;
        private String to;

        static PathSpec parse(String fileName, LineReader.Line line, String[] tokens,
                              int start) {
            PathSpec spec = new PathSpec();
            List<String> through = new ArrayList<>();
            String section = null;
            for (int i = start; i < tokens.length; i++) {
                switch (tokens[i]) {
                    case "from", "to" -> {
                        if (i + 1 >= tokens.length) {
                            throw LineReader.fail(fileName, line,
                                    "'" + tokens[i] + "' requires a pin reference");
                        }
                        String ref = PinReferences.parse(fileName, line, tokens[i + 1]).canonical();
                        if (tokens[i].equals("from")) {
                            if (spec.from != null) {
                                throw LineReader.fail(fileName, line, "duplicate 'from' clause");
                            }
                            spec.from = ref;
                        } else {
                            if (spec.to != null) {
                                throw LineReader.fail(fileName, line, "duplicate 'to' clause");
                            }
                            spec.to = ref;
                        }
                        i++;
                    }
                    case "through" -> section = "through";
                    default -> {
                        if (!"through".equals(section)) {
                            throw LineReader.fail(fileName, line,
                                    "unexpected token '" + tokens[i]
                                            + "' (expected from/through/to clauses)");
                        }
                        through.add(PinReferences.parse(fileName, line, tokens[i]).canonical());
                    }
                }
            }
            if (spec.from == null && spec.to == null && through.isEmpty()) {
                throw LineReader.fail(fileName, line,
                        "exception must constrain at least one of from/through/to");
            }
            spec.through = through.isEmpty() ? null : List.copyOf(through);
            return spec;
        }
    }
}
