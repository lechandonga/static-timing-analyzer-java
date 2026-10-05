package com.github.lechandonga.statictiminganalyzerjava.sta.parse;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.CellLibrary;

import java.nio.file.Path;
import java.util.List;

/**
 * 单元库格式（行式文本，# 不支持；注释用 //）：
 * <pre>
 * unit ns
 * cell BUF2
 *   pin A comb
 *   pin Y comb
 *   delay A Y 0.10
 * endcell
 * cell DFFX seq
 *   pin D seq_in
 *   pin CK clk
 *   pin Q seq_out
 *   timing setup=0.05 hold=0.02 clk_to_q=0.08
 * endcell
 * </pre>
 */
public class CellLibraryParser {

    public CellLibrary parse(Path file) {
        String src = file.toString();
        List<LineReader.RawLine> lines = LineReader.read(file);
        CellLibrary lib = new CellLibrary();
        CellLibrary.CellType current = null;
        boolean unitSeen = false;
        double scale = 1.0;

        for (LineReader.RawLine rl : lines) {
            String[] t = LineReader.tokens(rl, src, 1, 4);
            String head = t[0].toLowerCase();
            switch (head) {
                case "unit" -> {
                    if (t.length != 2) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "unit 用法: unit ns|ps");
                    }
                    if (!t[1].equals("ns") && !t[1].equals("ps")) {
                        throw new StaException("UNIT_UNSUPPORTED", src, rl.number(),
                                "只支持 ns/ps，收到: " + t[1]);
                    }
                    scale = t[1].equals("ps") ? 0.001 : 1.0;
                    if (unitSeen) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(), "unit 重复声明");
                    }
                    unitSeen = true;
                }
                case "cell" -> {
                    if (t.length != 3 || !t[2].equalsIgnoreCase("comb") && !t[2].equalsIgnoreCase("seq")) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "cell 用法: cell <名字> comb|seq");
                    }
                    if (!unitSeen) {
                        throw new StaException("UNIT_MISSING", src, rl.number(), "unit 声明必须位于第一个 cell 之前");
                    }
                    if (current != null) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "前一个 cell 缺少 endcell");
                    }
                    String name = t[1];
                    if (lib.cells().containsKey(name)) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(), "单元类型重复定义: " + name);
                    }
                    current = new CellLibrary.CellType(name, t[2].equalsIgnoreCase("seq"));
                    lib.cells().put(name, current);
                }
                case "endcell" -> {
                    if (current == null) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "endcell 没有匹配的 cell");
                    }
                    validate(current, src, rl.number());
                    current = null;
                }
                case "pin" -> {
                    requireCell(current, src, rl.number());
                    if (t.length != 3) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "pin 用法: pin <名字> comb|seq_in|clk|seq_out");
                    }
                    CellLibrary.PinRole role;
                    try {
                        role = CellLibrary.PinRole.valueOf(t[2].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        throw new StaException("VALUE_INVALID", src, rl.number(), "未知引脚角色: " + t[2]);
                    }
                    if (current.pins.containsKey(t[1])) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(),
                                "单元 " + current.name + " 引脚重复定义: " + t[1]);
                    }
                    current.pins.put(t[1], new CellLibrary.Pin(t[1], role));
                    switch (role) {
                        case CLK -> current.clkPin = t[1];
                        case SEQ_IN -> current.dataInPin = t[1];
                        case SEQ_OUT -> current.dataOutPin = t[1];
                        default -> {
                        }
                    }
                }
                case "delay" -> {
                    requireCell(current, src, rl.number());
                    if (current.sequential || t.length != 4) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "delay 仅用于组合单元，用法: delay <in> <out> <延时>");
                    }
                    double d = LineReader.number(t[3], src, rl.number());
                    String key = CellLibrary.CellType.arcKey(t[1], t[2]);
                    if (current.delays.containsKey(key)) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(),
                                "延时弧重复定义: " + key);
                    }
                    current.delays.put(key, d * scale);
                }
                case "timing" -> {
                    requireCell(current, src, rl.number());
                    if (!current.sequential) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "timing 仅用于时序单元");
                    }
                    double setup = Double.NaN;
                    double hold = Double.NaN;
                    double clkToQ = Double.NaN;
                    for (int i = 1; i < t.length; i++) {
                        String kv = t[i];
                        int eq = kv.indexOf('=');
                        if (eq <= 0) {
                            throw new StaException("SYNTAX_ERROR", src, rl.number(), "timing 参数形式 key=value: " + kv);
                        }
                        double v = LineReader.number(kv.substring(eq + 1), src, rl.number());
                        switch (kv.substring(0, eq)) {
                            case "setup" -> setup = v;
                            case "hold" -> hold = v;
                            case "clk_to_q" -> clkToQ = v;
                            default -> throw new StaException("VALUE_INVALID", src, rl.number(),
                                    "未知 timing 参数: " + kv.substring(0, eq));
                        }
                    }
                    if (!(Double.isNaN(current.setup) && Double.isNaN(current.hold) && Double.isNaN(current.clkToQ))) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(), "timing 重复定义");
                    }
                    if (Double.isNaN(setup) || Double.isNaN(hold) || Double.isNaN(clkToQ)) {
                        throw new StaException("VALUE_INVALID", src, rl.number(), "timing 必须同时给出 setup/hold/clk_to_q");
                    }
                    current.setup = setup * scale;
                    current.hold = hold * scale;
                    current.clkToQ = clkToQ * scale;
                }
                default -> throw new StaException("SYNTAX_ERROR", src, rl.number(), "未知关键字: " + head);
            }
        }
        if (current != null) {
            throw new StaException("SYNTAX_ERROR", src, 0, "文件结束时仍有未闭合的 cell");
        }
        if (!unitSeen) {
            throw new StaException("UNIT_MISSING", src, 0, "缺少 unit 声明");
        }
        if (lib.cells().isEmpty()) {
            throw new StaException("LIBRARY_EMPTY", src, 0, "单元库为空");
        }
        return lib;
    }

    private void requireCell(CellLibrary.CellType current, String src, int line) {
        if (current == null) {
            throw new StaException("SYNTAX_ERROR", src, line, "该行必须位于 cell/endcell 内");
        }
    }

    private void validate(CellLibrary.CellType c, String src, int line) {
        if (c.pins.size() < 2) {
            throw new StaException("CELL_DEF_INVALID", src, line, "单元 " + c.name + " 引脚数不足");
        }
        if (c.sequential) {
            if (c.clkPin == null || c.dataInPin == null || c.dataOutPin == null) {
                throw new StaException("CELL_DEF_INVALID", src, line,
                        "时序单元 " + c.name + " 必须各定义一个 clk/seq_in/seq_out 引脚");
            }
            if (Double.isNaN(c.setup)) {
                throw new StaException("CELL_DEF_INVALID", src, line, "时序单元 " + c.name + " 缺少 timing 定义");
            }
            long clkCount = c.pins.values().stream().filter(p -> p.role() == CellLibrary.PinRole.CLK).count();
            long inCount = c.pins.values().stream().filter(p -> p.role() == CellLibrary.PinRole.SEQ_IN).count();
            long outCount = c.pins.values().stream().filter(p -> p.role() == CellLibrary.PinRole.SEQ_OUT).count();
            if (clkCount != 1 || inCount != 1 || outCount != 1) {
                throw new StaException("CELL_DEF_INVALID", src, line,
                        "时序单元 " + c.name + " 的 clk/seq_in/seq_out 引脚必须各为一个");
            }
        } else {
            if (c.delays.isEmpty()) {
                throw new StaException("CELL_DEF_INVALID", src, line, "组合单元 " + c.name + " 缺少 delay 弧");
            }
            c.delays.forEach((k, v) -> {
                String[] ft = k.split("->");
                if (!c.pins.containsKey(ft[0]) || !c.pins.containsKey(ft[1])) {
                    throw new StaException("CELL_DEF_INVALID", src, line,
                            "单元 " + c.name + " 的延时弧引用了未定义引脚: " + k);
                }
            });
        }
    }
}
