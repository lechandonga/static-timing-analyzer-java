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
 * set_clock_uncertainty 0.05 -setup                 // 全局
 * set_clock_uncertainty 0.02 -hold -clock CLK       // 指定时钟
 * set_clock_uncertainty 0.10 -setup -from CLK -to U6/D  // 指定路径
 * set_timing_derate 0.95 -early                     // 全局
 * set_timing_derate 1.10 -late -cell_type BUF2      // 按单元类型
 * set_timing_derate 0.90 -early -instance U2        // 按实例
 * </pre>
 */
public class ConstraintsParser {

    public Constraints parse(Path file) {
        String src = file.toString();
        List<LineReader.RawLine> lines = LineReader.read(file);
        Constraints c = new Constraints();
        Set<String> exceptionKeys = new HashSet<>();
        Set<String> uncertaintyKeys = new HashSet<>();
        Set<String> derateKeys = new HashSet<>();

        for (LineReader.RawLine rl : lines) {
            String s = rl.text();
            String[] t = s.split("\\s+");
            switch (t[0]) {
                case "create_clock" -> parseClock(t, c, src, rl.number(), s);
                case "set_input_delay" -> parseIoDelay(t, c, true, src, rl.number());
                case "set_output_delay" -> parseIoDelay(t, c, false, src, rl.number());
                case "set_false_path" -> parseException(t, c, exceptionKeys, "FALSE_PATH", src, rl.number());
                case "set_multicycle_path" -> parseMultiCycle(t, c, exceptionKeys, src, rl.number());
                case "set_clock_uncertainty" -> parseUncertainty(t, c, uncertaintyKeys, src, rl.number());
                case "set_timing_derate" -> parseDerate(t, c, derateKeys, src, rl.number());
                default -> throw new StaException("SYNTAX_ERROR", src, rl.number(), "未知约束命令: " + t[0]);
            }
        }
        return c;
    }

    private void parseUncertainty(String[] t, Constraints c, Set<String> keys, String src, int line) {
        if (t.length < 2) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: set_clock_uncertainty <值> [-setup|-hold] [-clock <时钟> | -from <引用> -to <引用>]");
        }
        double value = dedicatedNumber(t[1], "UNCERTAINTY_INVALID", "时钟不确定度", src, line);
        String corner = null;
        String clock = null;
        String from = null;
        String to = null;
        int i = 2;
        while (i < t.length) {
            switch (t[i]) {
                case "-setup", "-hold" -> {
                    if (corner != null) {
                        throw new StaException("SYNTAX_ERROR", src, line, "-setup/-hold 只能出现一次");
                    }
                    corner = t[i].substring(1).toUpperCase();
                    i++;
                }
                case "-clock" -> {
                    if (clock != null || from != null || to != null || i + 1 >= t.length) {
                        throw new StaException("SYNTAX_ERROR", src, line,
                                "-clock 与 -from/-to 互斥，且各只能出现一次");
                    }
                    clock = t[i + 1];
                    i += 2;
                }
                case "-from" -> {
                    if (clock != null || from != null || i + 1 >= t.length) {
                        throw new StaException("SYNTAX_ERROR", src, line, "-from 非法或重复");
                    }
                    from = t[i + 1];
                    i += 2;
                }
                case "-to" -> {
                    if (clock != null || to != null || i + 1 >= t.length) {
                        throw new StaException("SYNTAX_ERROR", src, line, "-to 非法或重复");
                    }
                    to = t[i + 1];
                    i += 2;
                }
                default -> throw new StaException("SYNTAX_ERROR", src, line,
                        "set_clock_uncertainty 无法识别的参数: " + t[i]);
            }
        }
        if ((from == null) != (to == null)) {
            throw new StaException("SYNTAX_ERROR", src, line, "路径级不确定度必须同时给出 -from 与 -to");
        }
        String scope = from != null ? "PATH" : clock != null ? "CLOCK" : "GLOBAL";
        for (String cn : corner != null ? new String[]{corner} : new String[]{"SETUP", "HOLD"}) {
            Constraints.UncertaintyRule rule =
                    new Constraints.UncertaintyRule(cn, scope, clock, from, to, value);
            if (!keys.add(rule.key())) {
                throw new StaException("UNCERTAINTY_DUPLICATE", src, line,
                        "重复的不确定度声明: " + rule.key());
            }
            c.uncertainties().add(rule);
        }
    }

    private void parseDerate(String[] t, Constraints c, Set<String> keys, String src, int line) {
        if (t.length < 2) {
            throw new StaException("SYNTAX_ERROR", src, line,
                    "用法: set_timing_derate <系数> [-early|-late] [-cell_type <类型> | -instance <实例>]");
        }
        double factor = dedicatedNumber(t[1], "DERATE_INVALID", "降额系数", src, line);
        if (factor == 0) {
            throw new StaException("DERATE_INVALID", src, line, "降额系数必须为正数: " + t[1]);
        }
        String corner = null;
        String scope = "GLOBAL";
        String target = null;
        int i = 2;
        while (i < t.length) {
            switch (t[i]) {
                case "-early", "-late" -> {
                    if (corner != null) {
                        throw new StaException("SYNTAX_ERROR", src, line, "-early/-late 只能出现一次");
                    }
                    corner = t[i].substring(1).toUpperCase();
                    i++;
                }
                case "-cell_type", "-instance" -> {
                    if (target != null || i + 1 >= t.length) {
                        throw new StaException("SYNTAX_ERROR", src, line,
                                "-cell_type 与 -instance 互斥，且只能出现一次");
                    }
                    scope = t[i].equals("-cell_type") ? "CELL_TYPE" : "INSTANCE";
                    target = t[i + 1];
                    i += 2;
                }
                default -> throw new StaException("SYNTAX_ERROR", src, line,
                        "set_timing_derate 无法识别的参数: " + t[i]);
            }
        }
        for (String cn : corner != null ? new String[]{corner} : new String[]{"EARLY", "LATE"}) {
            Constraints.DerateRule rule = new Constraints.DerateRule(cn, scope, target, factor);
            if (!keys.add(rule.key())) {
                throw new StaException("DERATE_DUPLICATE", src, line,
                        "重复的降额声明: " + rule.key());
            }
            c.derates().add(rule);
        }
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

    /** 解析数值并以专用错误码失败，保证失败原因可与其它非法输入区分。 */
    private static double dedicatedNumber(String s, String code, String what, String src, int line) {
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            throw new StaException(code, src, line, what + "数值非法: " + s);
        }
        if (!Double.isFinite(v) || v < 0) {
            throw new StaException(code, src, line, what + "必须是非负有限数: " + s);
        }
        return v;
    }
}
