package com.github.lechandonga.statictiminganalyzerjava.sta.parse;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Constraints;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 约束格式（时间单位固定 ns）：
 * <pre>
 * create_clock CLK -period 10.0 -waveform {0.0 5.0}
 * create_clock CLK2 -period 6.0
 * set_input_delay  1.0 -clock CLK [get_ports IN1]
 * set_output_delay 0.5 -clock CLK [get_ports OUT1]
 * set_false_path -from CLK -to U2/D
 * set_multicycle_path 2 -from CLK -to U3/D
 * set_clock_uncertainty 0.05
 * set_clock_uncertainty 0.05 [get_clocks CLK]
 * set_clock_uncertainty 0.05 -from CLK1 -to U3/D
 * set_timing_derate 0.95
 * set_timing_derate -early 0.90 -late 1.10 -cell BUF
 * set_timing_derate -late 1.05 -instance U2
 * </pre>
 */
public class ConstraintsParser {

    public Constraints parse(Path file) {
        String src = file.toString();
        List<LineReader.RawLine> lines = LineReader.read(file);
        Constraints c = new Constraints();
        Set<String> exceptionKeys = new HashSet<>();

        for (LineReader.RawLine rl : lines) {
            String s = rl.text();
            String[] t = s.split("\\s+");
            switch (t[0]) {
                case "create_clock" -> parseClock(t, c, src, rl.number(), s);
                case "set_input_delay" -> parseIoDelay(t, c, true, src, rl.number());
                case "set_output_delay" -> parseIoDelay(t, c, false, src, rl.number());
                case "set_false_path" -> parseException(t, c, exceptionKeys, "FALSE_PATH", src, rl.number());
                case "set_multicycle_path" -> parseMultiCycle(t, c, exceptionKeys, src, rl.number());
                case "set_clock_uncertainty" -> parseUncertainty(t, c, src, rl.number());
                case "set_timing_derate" -> parseDerate(t, c, src, rl.number());
                default -> throw new StaException("SYNTAX_ERROR", src, rl.number(), "未知约束命令: " + t[0]);
            }
        }
        return c;
    }

    private void parseUncertainty(String[] t, Constraints c, String src, int line) {
        if (t.length < 2) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: set_clock_uncertainty <值> | <值> [get_clocks <时钟>] | <值> -from <起点> -to <终点>");
        }
        double value = LineReader.number(t[1], src, line);
        Constraints.UncertaintyRule rule;
        if (t.length == 2) {
            rule = Constraints.UncertaintyRule.global(value);
            if (c.globalUncertainty() != null) {
                throw new StaException("DUPLICATE_DEFINITION", src, line,
                        "全局时钟不确定度重复声明");
            }
            c.setGlobalUncertainty(value);
            return;
        }
        if ((t.length == 5 && t[2].equals("[get_clocks") && t[4].equals("]"))
                || (t.length == 4 && t[2].startsWith("[get_clocks") && t[3].endsWith("]"))) {
            String clock;
            if (t.length == 5) {
                clock = t[3];
            } else {
                String joined = (t[2] + " " + t[3]).substring("[get_clocks".length());
                clock = joined.substring(0, joined.length() - 1).trim();
                if (clock.isEmpty()) {
                    throw new StaException("SYNTAX_ERROR", src, line,
                            "用法: set_clock_uncertainty <值> [get_clocks <时钟>]");
                }
            }
            rule = Constraints.UncertaintyRule.clock(clock, value);
            if (c.clockUncertainty().containsKey(clock)) {
                throw new StaException("DUPLICATE_DEFINITION", src, line,
                        "时钟不确定度重复声明: " + clock);
            }
            c.clockUncertainty().put(clock, value);
        } else if (t.length == 6 && t[2].equals("-from") && t[4].equals("-to")) {
            rule = Constraints.UncertaintyRule.path(t[3], t[5], value);
            for (Constraints.UncertaintyRule existing : c.pathUncertainty()) {
                if (existing.from().equals(rule.from()) && existing.to().equals(rule.to())) {
                    throw new StaException("DUPLICATE_DEFINITION", src, line,
                            "路径级时钟不确定度重复声明: -from " + rule.from() + " -to " + rule.to());
                }
            }
            c.pathUncertainty().add(rule);
        } else {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: set_clock_uncertainty <值> | <值> [get_clocks <时钟>] | <值> -from <起点> -to <终点>");
        }
    }

    private void parseDerate(String[] t, Constraints c, String src, int line) {
        Double early = null;
        Double late = null;
        Double bare = null;
        String scope = "GLOBAL";
        String target = null;
        for (int i = 1; i < t.length; i++) {
            String tok = t[i];
            switch (tok) {
                case "-early" -> {
                    if (i + 1 >= t.length || early != null) {
                        throw derateSyntax(src, line);
                    }
                    early = positiveFactor(t[++i], src, line);
                }
                case "-late" -> {
                    if (i + 1 >= t.length || late != null) {
                        throw derateSyntax(src, line);
                    }
                    late = positiveFactor(t[++i], src, line);
                }
                case "-cell" -> {
                    if (i + 1 >= t.length || !"GLOBAL".equals(scope)) {
                        throw derateSyntax(src, line);
                    }
                    scope = "CELL";
                    target = t[++i];
                }
                case "-instance" -> {
                    if (i + 1 >= t.length || !"GLOBAL".equals(scope)) {
                        throw derateSyntax(src, line);
                    }
                    scope = "INSTANCE";
                    target = t[++i];
                }
                default -> {
                    if (tok.startsWith("-") || bare != null) {
                        throw derateSyntax(src, line);
                    }
                    bare = positiveFactor(tok, src, line);
                }
            }
        }
        if (bare != null && (early != null || late != null)) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "单个裸系数表示早晚相同，不能再与 -early/-late 混用");
        }
        double earlyFactor;
        double lateFactor;
        if (bare != null) {
            earlyFactor = bare;
            lateFactor = bare;
        } else {
            if (early == null && late == null) {
                throw derateSyntax(src, line);
            }
            earlyFactor = early != null ? early : late;
            lateFactor = late != null ? late : early;
        }
        Constraints.DerateRule rule = new Constraints.DerateRule(scope, target, earlyFactor, lateFactor);
        for (Constraints.DerateRule existing : c.derates()) {
            if (existing.key().equals(rule.key())) {
                throw new StaException("DUPLICATE_DEFINITION", src, line,
                        "时序降额重复声明: " + scope + (target != null ? " " + target : "")
                                + "（早晚角必须在同一条声明中给出）");
            }
        }
        c.derates().add(rule);
    }

    private StaException derateSyntax(String src, int line) {
        return new StaException("SYNTAX_ERROR", src, line,
                "用法: set_timing_derate <系数> | -early <系数> [-late <系数>] [-cell <单元类型> | -instance <实例名>]");
    }

    private double positiveFactor(String s, String src, int line) {
        double v = LineReader.number(s, src, line);
        if (!(v > 0)) {
            throw new StaException("VALUE_INVALID", src, line, "降额系数必须是大于 0 的有限数: " + s);
        }
        return v;
    }

    private void parseClock(String[] t, Constraints c, String src, int line, String raw) {
        if (t.length < 4 || !t[2].equals("-period")) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: create_clock <名字> -period <周期> [-waveform {r f}]");
        }
        String name = t[1];
        double period = LineReader.number(t[3], src, line);
        if (period <= 0) {
            throw new StaException("VALUE_INVALID", src, line, "时钟周期必须大于 0: " + name);
        }
        if (c.clocks().containsKey(name)) {
            throw new StaException("DUPLICATE_DEFINITION", src, line, "时钟重复定义: " + name);
        }
        double rise = 0;
        double fall = period / 2;
        int i = 4;
        if (i < t.length) {
            if (!t[i].equals("-waveform")) {
                throw new StaException("SYNTAX_ERROR", src, line, "-waveform 用法: -waveform {<rise> <fall>}");
            }
            int open = raw.indexOf('{');
            int close = raw.indexOf('}');
            if (open < 0 || close < open) {
                throw new StaException("SYNTAX_ERROR", src, line, "-waveform 必须使用 { } 给出两个边沿");
            }
            String rf = raw.substring(open + 1, close).trim();
            String[] edges = rf.split("\\s+");
            if (edges.length != 2) {
                throw new StaException("SYNTAX_ERROR", src, line, "-waveform 必须恰好包含两个边沿");
            }
            rise = LineReader.number(edges[0], src, line);
            fall = LineReader.number(edges[1], src, line);
            if (!(rise < fall) || fall >= period) {
                throw new StaException("VALUE_INVALID", src, line,
                        "波形边沿需满足 rise < fall < period");
            }
        }
        // 波形仅做合法性校验，本分析器采用单周期理想时钟，边沿偏移不参与计算（见文档）。
        c.clocks().put(name, new Constraints.Clock(name, name, period));
    }

    private void parseIoDelay(String[] t, Constraints c, boolean input, String src, int line) {
        double delay = LineReader.number(t[1], src, line);
        String clock;
        String port;
        if (t.length == 7 && t[2].equals("-clock") && t[4].equals("[get_ports") && t[6].equals("]")) {
            clock = t[3];
            port = t[5];
        } else if (t.length == 6 && t[2].equals("-clock")
                && t[4].startsWith("[get_ports") && t[5].endsWith("]")) {
            clock = t[3];
            String p5 = t[4].equals("[get_ports") ? null : t[4].substring("[get_ports".length()).trim();
            String p6 = t[5].substring(0, t[5].length() - 1);
            port = p5 != null && !p5.isEmpty() ? p5 : (p6.isEmpty() ? null : p6);
            if (port == null) {
                throw ioSyntax(input, src, line);
            }
        } else {
            throw ioSyntax(input, src, line);
        }
        var map = input ? c.inputDelays() : c.outputDelays();
        if (map.containsKey(port)) {
            throw new StaException("DUPLICATE_DEFINITION", src, line,
                    (input ? "输入" : "输出") + "延时重复定义: " + port);
        }
        map.put(port, new Constraints.IoDelay(clock, delay));
    }

    private StaException ioSyntax(boolean input, String src, int line) {
        return new StaException("SYNTAX_ERROR", src, line,
                "用法: " + (input ? "set_input_delay" : "set_output_delay")
                        + " <延时> -clock <时钟> [get_ports <端口>]");
    }

    private void parseException(String[] t, Constraints c, Set<String> keys, String kind, String src, int line) {
        if (t.length != 5 || !t[1].equals("-from") || !t[3].equals("-to")) {
            throw new StaException("SYNTAX_ERROR", src, line, "用法: set_false_path -from <起点> -to <终点>");
        }
        String from = t[2];
        String to = t[4];
        Constraints.ExceptionRule rule = new Constraints.ExceptionRule(kind, from, to, 0);
        if (!keys.add(rule.key())) {
            throw new StaException("DUPLICATE_DEFINITION", src, line,
                    "例外声明重复: -from " + from + " -to " + to);
        }
        c.exceptions().add(rule);
    }

    private void parseMultiCycle(String[] t, Constraints c, Set<String> keys, String src, int line) {
        if (t.length != 6 || !t[2].equals("-from") || !t[4].equals("-to")) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: set_multicycle_path <N> -from <起点> -to <终点>");
        }
        int n = LineReader.positiveInt(t[1], src, line);
        String from = t[3];
        String to = t[5];
        Constraints.ExceptionRule rule = new Constraints.ExceptionRule("MULTICYCLE", from, to, n);
        if (!keys.add(rule.key())) {
            throw new StaException("DUPLICATE_DEFINITION", src, line,
                    "多周期路径重复声明: -from " + from + " -to " + to);
        }
        c.exceptions().add(rule);
    }
}
