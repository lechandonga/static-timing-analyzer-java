package com.github.lechandonga.statictiminganalyzerjava.sta.analysis;

import java.util.ArrayList;
import java.util.List;

/** 一次完整时序分析的结论。所有集合均按名字排序，保证可复现。 */
public class TimingReport {

    public enum Status { OK, COMBINATIONAL_CYCLE }

    public enum PairCategory { CONSTRAINED, CDC, UNCONSTRAINED, FALSE_PATH }

    public enum EndpointStatus {
        SETUP_VIOLATED, HOLD_VIOLATED, SETUP_AND_HOLD_VIOLATED, MET, UNCONSTRAINED, CDC
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
        /** 建立检查使用的最晚（late）到达时间。 */
        public Double arrival;
        /** 保持检查使用的最早（early）到达时间。 */
        public Double earlyArrival;
        public Double setupRequired;
        public Double holdRequired;
        /** 建立/保持检查实际扣减/增加的时钟不确定度。 */
        public Double setupUncertainty;
        public Double holdUncertainty;
        /** 最晚/最早路径上各单元生效的降额系数说明。 */
        public String derateLate;
        public String derateEarly;
        /** 例外生效说明，例如 "FALSE_PATH"、"MULTICYCLE x2"；无例外为 null。 */
        public String exception;
        /** 最晚（建立）路径经过的单元/引脚序列。 */
        public List<String> dataPath = new ArrayList<>();
        /** 最早（保持）路径经过的单元/引脚序列。 */
        public List<String> earlyDataPath = new ArrayList<>();
    }

    public static class EndpointResult implements Comparable<EndpointResult> {
        public String endpoint;
        public String cell;
        public EndpointStatus status;
        public Double worstSetupSlack;
        public Double worstHoldSlack;
        /** 最差建立/保持路径对的完整数据（到达、要求、裕量、不确定度、降额、例外）。 */
        public PathPair worstSetup;
        public PathPair worstHold;
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
        /** 全部保持违例端点（按名字排序）。 */
        public List<String> holdViolationEndpoints = new ArrayList<>();
    }

    public Status status = Status.OK;
    public List<Cycle> cycles = new ArrayList<>();
    public List<EndpointResult> endpoints = new ArrayList<>();
    public Summary summary = new Summary();
    public List<String> notices = new ArrayList<>();
}
