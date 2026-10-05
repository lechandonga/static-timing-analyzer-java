package com.github.lechandonga.statictiminganalyzerjava.sta.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 设计网表：主输入、主输出、单元实例与线网。 */
public class Netlist {

    public static class PinRef {
        public final String instance;
        public final String pin;

        public PinRef(String instance, String pin) {
            this.instance = instance;
            this.pin = pin;
        }

        @Override
        public String toString() {
            return instance + "/" + pin;
        }
    }

    public static class Instance {
        public final String name;
        public final String cellType;
        public final Map<String, String> pinNet = new LinkedHashMap<>();

        public Instance(String name, String cellType) {
            this.name = name;
            this.cellType = cellType;
        }
    }

    public static class Net {
        public final String name;
        public String driver;
        public final Set<String> readers = new LinkedHashSet<>();

        public Net(String name) {
            this.name = name;
        }
    }

    private final Map<String, Instance> instances = new LinkedHashMap<>();
    private final Map<String, Net> nets = new LinkedHashMap<>();
    private final Set<String> primaryInputs = new LinkedHashSet<>();
    private final Set<String> primaryOutputs = new LinkedHashSet<>();

    public Map<String, Instance> instances() {
        return instances;
    }

    public Map<String, Net> nets() {
        return nets;
    }

    public Set<String> primaryInputs() {
        return primaryInputs;
    }

    public Set<String> primaryOutputs() {
        return primaryOutputs;
    }
}
