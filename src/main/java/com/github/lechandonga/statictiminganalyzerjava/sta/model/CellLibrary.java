package com.github.lechandonga.statictiminganalyzerjava.sta.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** 单元库：单元类型名 -> 单元类型定义。 */
public class CellLibrary {

    /** 单元引脚角色：COMB 普通组合引脚；SEQ_IN 时序单元数据输入；CLK 时钟脚；SEQ_OUT 时序输出。 */
    public enum PinRole { COMB, SEQ_IN, CLK, SEQ_OUT }

    public record Pin(String name, PinRole role) {
    }

    public static class CellType {
        public final String name;
        public final boolean sequential;
        public final Map<String, Pin> pins = new LinkedHashMap<>();
        /** 组合单元：弧 (fromPin,toPin) -> 固定延时(ns)。 */
        public final Map<String, Double> delays = new LinkedHashMap<>();
        /** 时序单元：setup(ns)，hold(ns)，clk->q 延时(ns)。 */
        public double setup = Double.NaN;
        public double hold = Double.NaN;
        public double clkToQ = Double.NaN;
        public String clkPin;
        public String dataInPin;
        public String dataOutPin;

        public CellType(String name, boolean sequential) {
            this.name = name;
            this.sequential = sequential;
        }

        public static String arcKey(String from, String to) {
            return from + "->" + to;
        }
    }

    private final Map<String, CellType> cells = new LinkedHashMap<>();

    public Map<String, CellType> cells() {
        return cells;
    }
}
