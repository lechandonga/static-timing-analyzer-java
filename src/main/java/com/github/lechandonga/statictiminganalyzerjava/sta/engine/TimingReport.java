package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import java.util.ArrayList;
import java.util.List;

/** Result of one static timing analysis run. All numeric values are in picoseconds. */
public final class TimingReport {

    public enum Category {
        /** All same-clock checks pass. */
        MET,
        /** At least one setup or hold check fails. */
        VIOLATED,
        /** Launch and capture clocks differ; not checked against a single period. */
        CROSS_CLOCK_DOMAIN,
        /** Endpoint or its data path lacks clock/port coverage. */
        UNCONSTRAINED,
        /** Path disabled by a false-path exception. */
        FALSE_PATH
    }

    public static final class PathHop {
        public String node;
        public String via;
        public double delayPs;

        public PathHop() {
        }

        public PathHop(String node, String via, double delayPs) {
            this.node = node;
            this.via = via;
            this.delayPs = delayPs;
        }
    }

    public static final class EndpointResult {
        public String endpoint;
        public String endpointKind;
        public String instance;
        public String cell;
        public String captureClock;
        public List<String> launchClocks = new ArrayList<>();
        public Category category;
        public Double setupSlackPs;
        public Double holdSlackPs;
        public double arrivalPs;
        public Double setupRequiredPs;
        public Double holdRequiredPs;
        public List<PathHop> worstSetupPath = new ArrayList<>();
        public List<PathHop> worstHoldPath = new ArrayList<>();
        public String appliedException;
        public String reason;
    }

    public static final class Summary {
        public int totalEndpoints;
        public int met;
        public int violated;
        public int setupViolations;
        public int holdViolations;
        public int crossClockDomain;
        public int unconstrained;
        public int falsePaths;
        public double worstSetupSlackPs;
        public double worstHoldSlackPs;
        public String worstSetupEndpoint;
        public String worstHoldEndpoint;
    }

    public Summary summary = new Summary();
    public List<EndpointResult> endpoints = new ArrayList<>();
    /** Combinational loops found; analysis aborts when non-empty. */
    public List<List<String>> combinationalLoops = new ArrayList<>();
    public List<String> unconstrainedReasons = new ArrayList<>();
    public List<String> exceptionApplications = new ArrayList<>();
}
