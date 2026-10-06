package com.github.lechandonga.statictiminganalyzerjava.sta.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 时序约束：时钟、输入输出延时、伪路径与多周期路径例外。 */
public class Constraints {

    public static class Clock {
        public final String name;
        public final String sourcePin;
        public final double periodNs;

        public Clock(String name, String sourcePin, double periodNs) {
            this.name = name;
            this.sourcePin = sourcePin;
            this.periodNs = periodNs;
        }
    }

    public record IoDelay(String clock, double delayNs) {
    }

    public static class ExceptionRule {
        /** FALSE_PATH 或 MULTICYCLE。 */
        public final String kind;
        public final String from;
        public final String to;
        /** multicycle 路径的周期倍数，false path 无意义。 */
        public final int multiplier;

        public ExceptionRule(String kind, String from, String to, int multiplier) {
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.multiplier = multiplier;
        }

        public String key() {
            return kind + "|" + from + "|" + to;
        }

        public String kind() {
            return kind;
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        public int multiplier() {
            return multiplier;
        }
    }

    private final Map<String, Clock> clocks = new LinkedHashMap<>();
    /** 主输入名 -> 输入延时。 */
    private final Map<String, IoDelay> inputDelays = new LinkedHashMap<>();
    /** 主输出名 -> 输出延时。 */
    private final Map<String, IoDelay> outputDelays = new LinkedHashMap<>();
    private final List<ExceptionRule> exceptions = new ArrayList<>();
    /** 全局时钟不确定度（ns）；未声明为 null。 */
    private Double globalUncertainty;
    /** 单个时钟的不确定度（ns），时钟名 -> 值。 */
    private final Map<String, Double> clockUncertainty = new LinkedHashMap<>();
    /** 路径级不确定度，按解析顺序保存，分析期按例外同款粒度仲裁。 */
    private final List<UncertaintyRule> pathUncertainty = new ArrayList<>();
    /** 时序降额声明，按解析顺序保存；scope 决定全局/单元/实例粒度。 */
    private final List<DerateRule> derates = new ArrayList<>();

    public Map<String, Clock> clocks() {
        return clocks;
    }

    public Map<String, IoDelay> inputDelays() {
        return inputDelays;
    }

    public Map<String, IoDelay> outputDelays() {
        return outputDelays;
    }

    public List<ExceptionRule> exceptions() {
        return exceptions;
    }

    /** 时钟不确定度声明：GLOBAL / CLOCK / PATH 三种粒度。 */
    public static class UncertaintyRule {
        public final String kind;
        /** CLOCK 粒度的时钟名；GLOBAL 为 null。 */
        public final String clock;
        /** PATH 粒度的 -from / -to 引用；其余粒度为 null。 */
        public final String from;
        public final String to;
        public final double valueNs;

        private UncertaintyRule(String kind, String clock, String from, String to, double valueNs) {
            this.kind = kind;
            this.clock = clock;
            this.from = from;
            this.to = to;
            this.valueNs = valueNs;
        }

        public static UncertaintyRule global(double valueNs) {
            return new UncertaintyRule("GLOBAL", null, null, null, valueNs);
        }

        public static UncertaintyRule clock(String clock, double valueNs) {
            return new UncertaintyRule("CLOCK", clock, null, null, valueNs);
        }

        public static UncertaintyRule path(String from, String to, double valueNs) {
            return new UncertaintyRule("PATH", null, from, to, valueNs);
        }

        public String key() {
            return kind + "|" + (clock != null ? clock : "") + "|"
                    + (from != null ? from : "") + "|" + (to != null ? to : "");
        }

        public String kind() {
            return kind;
        }

        public String clock() {
            return clock;
        }

        public String from() {
            return from;
        }

        public String to() {
            return to;
        }

        public double valueNs() {
            return valueNs;
        }
    }

    /** 时序降额声明：CELL 按单元类型、INSTANCE 按实例、GLOBAL 全局；factor 分早/晚。 */
    public static class DerateRule {
        public final String scope;
        /** CELL 为单元类型名；INSTANCE 为实例名；GLOBAL 为 null。 */
        public final String target;
        /** 早角（保持检查）系数；与晚角相同时两侧相等。 */
        public final double earlyFactor;
        /** 晚角（建立检查）系数。 */
        public final double lateFactor;

        public DerateRule(String scope, String target, double earlyFactor, double lateFactor) {
            this.scope = scope;
            this.target = target;
            this.earlyFactor = earlyFactor;
            this.lateFactor = lateFactor;
        }

        public String key() {
            return scope + "|" + (target != null ? target : "");
        }
    }

    public Double globalUncertainty() {
        return globalUncertainty;
    }

    public void setGlobalUncertainty(double valueNs) {
        this.globalUncertainty = valueNs;
    }

    public Map<String, Double> clockUncertainty() {
        return clockUncertainty;
    }

    public List<UncertaintyRule> pathUncertainty() {
        return pathUncertainty;
    }

    public List<DerateRule> derates() {
        return derates;
    }
}
