package com.github.lechandonga.statictiminganalyzerjava.sta.constraint;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimeUnit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Timing constraints: clocks, port delays and timing exceptions. */
public final class Constraints {

    private TimeUnit timeUnit = TimeUnit.PS;
    private final Map<String, Clock> clocks = new LinkedHashMap<>();
    /** key = input port name. */
    private final Map<String, Double> inputDelaysPs = new LinkedHashMap<>();
    /** key = output port name. */
    private final Map<String, Double> outputDelaysPs = new LinkedHashMap<>();
    /** clock name referenced by each set_*_delay statement (validated at link time). */
    private final Map<String, String> portDelayClock = new LinkedHashMap<>();
    private final List<ExceptionRule> exceptions = new ArrayList<>();

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public void setTimeUnit(TimeUnit timeUnit) {
        this.timeUnit = timeUnit;
    }

    public Map<String, Clock> getClocks() {
        return clocks;
    }

    public Map<String, Double> getInputDelaysPs() {
        return inputDelaysPs;
    }

    public Map<String, Double> getOutputDelaysPs() {
        return outputDelaysPs;
    }

    public Map<String, String> getPortDelayClock() {
        return portDelayClock;
    }

    public List<ExceptionRule> getExceptions() {
        return exceptions;
    }

    public void addClock(Clock clock) {
        if (clocks.containsKey(clock.name())) {
            throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                    "clock '" + clock.name() + "' is defined more than once");
        }
        clocks.put(clock.name(), clock);
    }

    /**
     * Clock definition. Only a single edge per period is modelled (rising edge at time 0);
     * uncertainty is shared setup/hold jitter applied symmetrically.
     */
    public record Clock(String name, double periodPs, double uncertaintyPs,
                        List<String> sourcePins) {
    }

    public enum ExceptionKind {
        FALSE_PATH,
        MULTICYCLE_PATH
    }

    /**
     * A timing exception.
     *
     * <p>{@code from} / {@code through} / {@code to} are canonical pin/port identifiers;
     * {@code through} is an ordered list that the path must traverse in that order.
     * Any of the three may be {@code null} (wildcard).
     * For multicycle paths, {@code setupCycles}/{@code holdCycles} carry the multipliers.
     */
    public record ExceptionRule(int line, ExceptionKind kind, String from,
                                List<String> through, String to,
                                Integer setupCycles, Integer holdCycles) {

        public String signature() {
            return kind + "|from=" + nullStar(from) + "|through="
                    + String.join(">", through == null ? List.of() : through)
                    + "|to=" + nullStar(to);
        }

        private static String nullStar(String value) {
            return value == null ? "*" : value;
        }
    }

    public void addException(ExceptionRule rule) {
        for (ExceptionRule existing : exceptions) {
            if (samePatternAndMode(existing, rule) && sameEffect(existing, rule)) {
                throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                        "duplicate " + rule.kind() + " exception at line " + rule.line()
                                + " (same path pattern and effect already declared at line "
                                + existing.line() + ")");
            }
            if (samePatternAndMode(existing, rule) && !sameEffect(existing, rule)) {
                throw new StaException(StaException.Code.CONSTRAINT_CONFLICT,
                        rule.kind() + " exception at line " + rule.line()
                                + " conflicts with the same-pattern declaration at line "
                                + existing.line() + " (different cycle multipliers)");
            }
        }
        exceptions.add(rule);
    }

    private static boolean samePatternAndMode(ExceptionRule existing, ExceptionRule rule) {
        return existing.kind() == rule.kind()
                && java.util.Objects.equals(existing.from(), rule.from())
                && java.util.Objects.equals(existing.to(), rule.to())
                && java.util.Objects.equals(
                        existing.through() == null ? null : List.copyOf(existing.through()),
                        rule.through() == null ? null : List.copyOf(rule.through()))
                && (existing.setupCycles() != null) == (rule.setupCycles() != null);
    }

    private static boolean sameEffect(ExceptionRule a, ExceptionRule b) {
        return java.util.Objects.equals(a.setupCycles(), b.setupCycles())
                && java.util.Objects.equals(a.holdCycles(), b.holdCycles());
    }

}
