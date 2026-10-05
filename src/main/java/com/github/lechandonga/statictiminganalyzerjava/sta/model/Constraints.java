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
}
