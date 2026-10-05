package com.github.lechandonga.statictiminganalyzerjava.sta.analysis;

import java.util.ArrayList;
import java.util.List;

/** 一次完整时序分析的结论。所有集合均按名字排序，保证可复现。 */
public class TimingReport {

    public enum Status { OK, COMBINATIONAL_CYCLE }

    public enum PairCategory { CONSTRAINED, CDC, UNCONSTRAINED, FALSE_PATH }

    public enum EndpointStatus {
        SETUP_VIOLATED, HOLD_VIOLATED, MET, UNCONSTRAINED, CDC
    }

    public static class Cycle {
        public List<String> nodes = new ArrayList<>();
        public String description;
    }

    public static class PathPair {
        public String startpoint;
        public String launchClock;
        public String captureClock;
        public PairCategory category;
        public Double setupSlack;
        public Double holdSlack;
        public Double arrival;
        public Double setupRequired;
        public Double holdRequired;
        /** 例外生效说明，例如 "FALSE_PATH"、"MULTICYCLE x2"；无例外为 null。 */
        public String exception;
        public List<String> dataPath = new ArrayList<>();
    }

    public static class EndpointResult implements Comparable<EndpointResult> {
        public String endpoint;
        public String cell;
        public EndpointStatus status;
        public Double worstSetupSlack;
        public Double worstHoldSlack;
        public List<PathPair> pairs = new ArrayList<>();
        /** 最差 setup 路径经过的单元/引脚序列。 */
        public List<String> worstSetupPath = new ArrayList<>();
        public List<String> worstHoldPath = new ArrayList<>();
        public String note;

        @Override
        public int compareTo(EndpointResult o) {
            return endpoint.compareTo(o.endpoint);
        }
    }

    public static class Summary {
        public int totalEndpoints;
        public int setupViolations;
        public int holdViolations;
        public int met;
        public int unconstrained;
        public int cdc;
        public int falsePathPairs;
        public int uncoveredPairs;
        public int cdcPairs;
        public double worstSetupSlack;
        public String worstSetupEndpoint;
        public double worstHoldSlack;
        public String worstHoldEndpoint;
    }

    public Status status = Status.OK;
    public List<Cycle> cycles = new ArrayList<>();
    public List<EndpointResult> endpoints = new ArrayList<>();
    public Summary summary = new Summary();
    public List<String> notices = new ArrayList<>();
}
