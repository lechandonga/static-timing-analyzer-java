package com.github.lechandonga.statictiminganalyzerjava.sta.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable domain model of the local static timing analyzer. */
public final class Models {

    private Models() {
    }

    /** Supported time units. All analysis math is performed in picoseconds. */
    public enum TimeUnit {
        PS(1.0), NS(1000.0);

        private final double toPs;

        TimeUnit(double toPs) {
            this.toPs = toPs;
        }

        public double toPs(double value) {
            return value * toPs;
        }

        public static TimeUnit parse(String token) {
            return switch (token.toLowerCase(java.util.Locale.ROOT)) {
                case "ps" -> PS;
                case "ns" -> NS;
                default -> throw new IllegalArgumentException("unsupported time unit '" + token
                        + "' (allowed: ps, ns)");
            };
        }
    }

    /** A timing arc of a library cell. Pin names are local to the cell. */
    public record TimingArc(String fromPin, String toPin, ArcType type,
                            double riseDelayPs, double fallDelayPs) {
        public enum ArcType {
            /** Combinational: input pin -&gt; output pin. */
            COMBINATIONAL,
            /** Sequential: clock pin -&gt; output pin (clock-to-Q delay). */
            CLOCK_TO_Q,
            /** Setup constraint: data pin checked against clock pin. */
            SETUP,
            /** Hold constraint: data pin checked against clock pin. */
            HOLD
        }

        public double delayPs() {
            return Math.max(riseDelayPs, fallDelayPs);
        }

        public double checkPs() {
            return Math.max(riseDelayPs, fallDelayPs);
        }
    }

    /** Cell definition in the unit library. */
    public static final class Cell {
        private final String name;
        private final Map<String, List<TimingArc>> arcs = new LinkedHashMap<>();
        private final java.util.Set<String> pins = new java.util.LinkedHashSet<>();

        public Cell(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public void addArc(TimingArc arc) {
            arcs.computeIfAbsent(arc.fromPin(), k -> new ArrayList<>()).add(arc);
            pins.add(arc.fromPin());
            pins.add(arc.toPin());
        }

        public List<TimingArc> arcsFrom(String pin) {
            return arcs.getOrDefault(pin, List.of());
        }

        public java.util.Set<String> getPins() {
            return pins;
        }

        public TimingArc findArc(String from, String to, TimingArc.ArcType type) {
            for (TimingArc arc : arcsFrom(from)) {
                if (arc.toPin().equals(to) && arc.type() == type) {
                    return arc;
                }
            }
            return null;
        }
    }

    /** Parsed unit library. */
    public static final class Library {
        private TimeUnit timeUnit = TimeUnit.PS;
        private final Map<String, Cell> cells = new LinkedHashMap<>();

        public TimeUnit getTimeUnit() {
            return timeUnit;
        }

        public void setTimeUnit(TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
        }

        public Map<String, Cell> getCells() {
            return cells;
        }
    }

    /** An instance pin or a top-level port, referenced canonically as {@code cell/pin} or {@code port}. */
    public record PinRef(String instance, String pin) {
        public boolean isPort() {
            return instance == null;
        }

        public String canonical() {
            return isPort() ? pin : instance + "/" + pin;
        }

        public static PinRef port(String name) {
            return new PinRef(null, name);
        }

        public static PinRef pin(String instance, String pin) {
            return new PinRef(instance, pin);
        }
    }

    /** Netlist instance of a library cell. */
    public static final class Instance {
        private final String name;
        private final String cellName;

        public Instance(String name, String cellName) {
            this.name = name;
            this.cellName = cellName;
        }

        public String getName() {
            return name;
        }

        public String getCellName() {
            return cellName;
        }
    }

    /**
     * A net: one driver pin/port and zero or more load pins/ports.
     * Net delay is modelled as zero; interconnect delay can be added per load if needed.
     */
    public static final class Net {
        private final String name;
        private final PinRef driver;
        private final List<PinRef> loads = new ArrayList<>();

        public Net(String name, PinRef driver) {
            this.name = name;
            this.driver = driver;
        }

        public String getName() {
            return name;
        }

        public PinRef getDriver() {
            return driver;
        }

        public List<PinRef> getLoads() {
            return loads;
        }
    }

    /** Parsed netlist. */
    public static final class Netlist {
        private final List<String> inputPorts = new ArrayList<>();
        private final List<String> outputPorts = new ArrayList<>();
        private final Map<String, Instance> instances = new LinkedHashMap<>();
        private final Map<String, Net> nets = new LinkedHashMap<>();

        public List<String> getInputPorts() {
            return inputPorts;
        }

        public List<String> getOutputPorts() {
            return outputPorts;
        }

        public Map<String, Instance> getInstances() {
            return instances;
        }

        public Map<String, Net> getNets() {
            return nets;
        }
    }
}
