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
        /** 晚角（建立检查）数据到达时间。 */
        public Double arrival;
        /** 早角（保持检查）数据到达时间。 */
        public Double holdArrival;
        public Double setupRequired;
        public Double holdRequired;
        /** 建立/保持检查实际扣减/加回的时钟不确定度（ns）。 */
        public Double setupUncertainty;
        public Double holdUncertainty;
        /** 不确定度来源说明：全局/时钟/路径级，含仲裁依据。 */
        public String setupUncertaintySource;
        public String holdUncertaintySource;
        /** 沿路径生效的降额系数明细（晚角/早角），元素形如 "U2[BUF]*1.100(cell)"。 */
        public List<String> setupDerates = new ArrayList<>();
        public List<String> holdDerates = new ArrayList<>();
        /** 例外生效说明，例如 "FALSE_PATH"、"MULTICYCLE x2"；无例外为 null。 */
        public String exception;
        /** 晚角（建立）最差路径经过的单元/引脚序列。 */
        public List<String> dataPath = new ArrayList<>();
        /** 早角（保持）最差路径经过的单元/引脚序列。 */
        public List<String> holdDataPath = new ArrayList<>();
    }

    public static class EndpointResult implements Comparable<EndpointResult> {
        public String endpoint;
        public String cell;
        public EndpointStatus status;
        public Double worstSetupSlack;
        public Double worstHoldSlack;
        /** 最差 setup/hold 对应的完整路径对（含到达、要求、不确定度、降额、例外来源）。 */
        public PathPair worstSetupPair;
        public PathPair worstHoldPair;
        public List<PathPair> pairs = new ArrayList<>();
        /** 最差 setup 路径经过的单元/引脚序列（与 worstSetupPair.dataPath 相同，保留旧字段）。 */
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
    /** 全部保持违例端点（含建立/保持同时违例），按 slack 升序、名字次序排序。 */
    public List<EndpointResult> holdViolations = new ArrayList<>();
    public List<String> notices = new ArrayList<>();
}
