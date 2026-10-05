package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.Clock;
import com.github.lechandonga.statictiminganalyzerjava.sta.constraint.Constraints.ExceptionRule;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.ExceptionResolver.Decision;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingGraph.Edge;
import com.github.lechandonga.statictiminganalyzerjava.sta.engine.TimingGraph.NodeData;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Library;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Netlist;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimingArc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic single-corner static timing analysis.
 *
 * <p>Each arc uses the worst of its rise/fall delay; the same value feeds setup and hold,
 * keeping both checks on one arrival-time basis. All collections iterate in insertion or
 * name order and results are rounded to {@link #SCALE} decimals, so repeated runs and
 * declaration reordering produce identical reports.
 */
public final class TimingEngine {

    private static final int SCALE = 6;

    private TimingEngine() {
    }

    public static TimingReport analyze(Netlist netlist, Library library,
                                       Constraints constraints) {
        TimingGraph graph = TimingGraph.build(netlist, library);
        validateConstraints(graph, netlist, constraints);

        Map<String, String> clockAtPin = resolveClockDomains(graph, constraints);

        List<List<String>> loops = CycleDetector.findLoops(graph.dataEdges,
                new ArrayList<>(graph.nodes.keySet()));
        if (!loops.isEmpty()) {
            throw new CombinationalLoopException(loops);
        }

        List<String> topo = topologicalOrder(graph);
        Map<String, List<Tag>> arrivals =
                propagate(graph, topo, constraints, clockAtPin);
        return evaluate(graph, constraints, clockAtPin, arrivals, topo);
    }

    static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return value;
        }
        return java.math.BigDecimal.valueOf(value)
                .setScale(SCALE, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    // ---------------------------------------------------------------- constraints

    private static void validateConstraints(TimingGraph graph, Netlist netlist,
                                            Constraints constraints) {
        for (Map.Entry<String, String> ref : constraints.getPortDelayClock().entrySet()) {
            String statement = ref.getKey();
            String clockName = ref.getValue();
            if (!constraints.getClocks().containsKey(clockName)) {
                throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                        statement + " references unknown clock '" + clockName + "'");
            }
            String port = statement.substring(statement.indexOf(':') + 1);
            boolean input = statement.startsWith("set_input_delay");
            List<String> ports = input ? netlist.getInputPorts() : netlist.getOutputPorts();
            if (!ports.contains(port)) {
                throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                        statement + " targets port '" + port
                                + "' which is not a declared "
                                + (input ? "input" : "output") + " port");
            }
        }
        for (Clock clock : constraints.getClocks().values()) {
            for (String source : clock.sourcePins()) {
                if (!graph.nodes.containsKey(source)) {
                    throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                            "clock '" + clock.name() + "' source '" + source
                                    + "' does not exist in the netlist");
                }
            }
        }
        for (ExceptionRule rule : constraints.getExceptions()) {
            checkExceptionRef(graph, rule, rule.from(), "from");
            checkExceptionRef(graph, rule, rule.to(), "to");
            if (rule.through() != null) {
                for (String through : rule.through()) {
                    checkExceptionRef(graph, rule, through, "through");
                }
            }
            if (rule.setupCycles() != null && rule.setupCycles() < 1) {
                throw new StaException(StaException.Code.ILLEGAL_VALUE,
                        "setup multicycle at line " + rule.line() + " must be >= 1");
            }
            if (rule.holdCycles() != null && rule.holdCycles() < 0) {
                throw new StaException(StaException.Code.ILLEGAL_VALUE,
                        "hold multicycle at line " + rule.line() + " must be >= 0");
            }
        }
    }

    private static void checkExceptionRef(TimingGraph graph, ExceptionRule rule,
                                          String ref, String clause) {
        if (ref != null && !graph.nodes.containsKey(ref)) {
            throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                    rule.kind() + " exception at line " + rule.line() + " has " + clause
                            + " reference '" + ref + "' which does not exist");
        }
    }

    // ---------------------------------------------------------------- clocks

    private static Map<String, String> resolveClockDomains(TimingGraph graph,
                                                           Constraints constraints) {
        Map<String, String> sourceOwner = new HashMap<>();
        for (Clock clock : constraints.getClocks().values()) {
            for (String source : clock.sourcePins()) {
                String previous = sourceOwner.put(source, clock.name());
                if (previous != null && !previous.equals(clock.name())) {
                    throw new StaException(StaException.Code.CONSTRAINT_CONFLICT,
                            "clock source '" + source + "' is driven by both clock '"
                                    + previous + "' and clock '" + clock.name() + "'");
                }
            }
        }
        Map<String, String> reached = new LinkedHashMap<>();
        for (String source : new ArrayList<>(sourceOwner.keySet())) {
            walkClock(graph, source, sourceOwner.get(source), reached);
        }
        return reached;
    }

    private static void walkClock(TimingGraph graph, String source, String clockName,
                                  Map<String, String> reached) {
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(source));
        while (!queue.isEmpty()) {
            String node = queue.poll();
            String existing = reached.get(node);
            if (existing != null) {
                if (!existing.equals(clockName)) {
                    throw new StaException(StaException.Code.CONSTRAINT_CONFLICT,
                            "pin '" + node + "' is reachable from multiple clock sources ('"
                                    + existing + "' and '" + clockName
                                    + "'); split the domains explicitly");
                }
                continue;
            }
            reached.put(node, clockName);
            NodeData data = graph.nodes.get(node);
            for (Edge edge : graph.dataEdges.getOrDefault(node, List.of())) {
                NodeData target = graph.nodes.get(edge.to);
                if (target == null) {
                    continue;
                }
                boolean crossingInstance = data != null && data.instance != null
                        && target.instance != null && !data.instance.equals(target.instance);
                boolean leavesPort = data != null && (data.inputPort || data.outputPort);
                if (crossingInstance || leavesPort) {
                    queue.add(edge.to);
                }
            }
        }
    }

    // ---------------------------------------------------------------- topology

    private static List<String> topologicalOrder(TimingGraph graph) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        for (String node : graph.nodes.keySet()) {
            indegree.put(node, 0);
        }
        for (List<Edge> edges : graph.dataEdges.values()) {
            for (Edge edge : edges) {
                indegree.merge(edge.to, 1, Integer::sum);
            }
        }
        List<String> ready = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            ready.sort(Comparator.naturalOrder());
            String node = ready.remove(0);
            order.add(node);
            for (Edge edge : graph.dataEdges.getOrDefault(node, List.of())) {
                int value = indegree.get(edge.to) - 1;
                indegree.put(edge.to, value);
                if (value == 0) {
                    ready.add(edge.to);
                }
            }
        }
        if (order.size() != graph.nodes.size()) {
            throw new IllegalStateException(
                    "internal error: topological sort failed after loop detection");
        }
        return order;
    }

    // ---------------------------------------------------------------- propagation

    static final class Tag {
        String clock;
        double arrivalPs;
        String startNode;
        List<TimingReport.PathHop> hops = new ArrayList<>();

        Tag extended(String node, Edge edge) {
            Tag tag = new Tag();
            tag.clock = clock;
            tag.arrivalPs = round(arrivalPs + edge.delayPs);
            tag.startNode = startNode;
            tag.hops = new ArrayList<>(hops);
            tag.hops.add(new TimingReport.PathHop(node, edge.via, edge.delayPs));
            return tag;
        }

        static Tag launched(String node, String clock, Edge edge) {
            Tag tag = new Tag();
            tag.clock = clock;
            tag.startNode = node;
            tag.arrivalPs = round(edge.delayPs);
            tag.hops.add(new TimingReport.PathHop(node, edge.via, edge.delayPs));
            return tag;
        }
    }

    private static Map<String, List<Tag>> propagate(TimingGraph graph, List<String> topo,
                                                    Constraints constraints,
                                                    Map<String, String> clockAtPin) {
        Map<String, List<Edge>> incoming = new LinkedHashMap<>();
        for (String node : graph.nodes.keySet()) {
            incoming.put(node, new ArrayList<>());
        }
        for (Map.Entry<String, List<Edge>> entry : graph.dataEdges.entrySet()) {
            for (Edge edge : entry.getValue()) {
                Edge withSource = new Edge(edge.to, edge.delayPs,
                        entry.getKey() + "|" + edge.via, edge.clockToQ);
                incoming.get(edge.to).add(withSource);
            }
        }

        Map<String, List<Tag>> at = new LinkedHashMap<>();
        for (String node : topo) {
            NodeData data = graph.nodes.get(node);
            List<Tag> tags = new ArrayList<>();
            if (data.inputPort) {
                Tag tag = new Tag();
                String inputClockName = constraints.getPortDelayClock()
                        .get("set_input_delay:" + node);
                tag.clock = inputClockName != null ? inputClockName : clockAtPin.get(node);
                tag.startNode = node;
                tag.arrivalPs = round(constraints.getInputDelaysPs().getOrDefault(node, 0.0));
                tag.hops.add(new TimingReport.PathHop(node, "input_port", 0.0));
                tags.add(tag);
            }
            for (Edge edge : incoming.get(node)) {
                String predecessor = edge.via.substring(0, edge.via.indexOf('|'));
                String via = edge.via.substring(edge.via.indexOf('|') + 1);
                Edge clean = new Edge(node, edge.delayPs, via, edge.clockToQ);
                for (Tag parent : at.getOrDefault(predecessor, List.of())) {
                    tags.add(edge.clockToQ
                            ? Tag.launched(node, ownerClock(graph, clockAtPin, node), clean)
                            : parent.extended(node, clean));
                }
            }
            at.put(node, mergeTags(tags));
        }
        return at;
    }

    private static List<Tag> mergeTags(List<Tag> tags) {
        Map<String, Tag> best = new LinkedHashMap<>();
        for (Tag tag : tags) {
            String key = tag.clock == null ? "\0unclocked:" + tag.startNode : tag.clock;
            Tag incumbent = best.get(key);
            if (incumbent == null || tag.arrivalPs > incumbent.arrivalPs) {
                best.put(key, tag);
            }
        }
        return new ArrayList<>(best.values());
    }

    private static boolean isSequentialDataPin(NodeData data) {
        return data != null && data.cell != null
                && (data.setupArc != null || data.holdArc != null);
    }

    /** Clock that launches data out of a sequential cell: the clock arriving on its clock pin. */
    private static String ownerClock(TimingGraph graph, Map<String, String> clockAtPin,
                                     String outputPin) {
        NodeData data = graph.nodes.get(outputPin);
        if (data == null || data.cell == null) {
            return clockAtPin.get(outputPin);
        }
        for (String pin : data.cell.getPins()) {
            for (TimingArc arcCandidate : data.cell.arcsFrom(pin)) {
                if (arcCandidate.type() == TimingArc.ArcType.CLOCK_TO_Q
                        && arcCandidate.toPin().equals(data.localPin)) {
                    return clockAtPin.get(data.instance + "/" + arcCandidate.fromPin());
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- evaluation

    private static TimingReport evaluate(TimingGraph graph, Constraints constraints,
                                         Map<String, String> clockAtPin,
                                         Map<String, List<Tag>> arrivals,
                                         List<String> topo) {
        TimingReport report = new TimingReport();
        for (String node : topo) {
            NodeData data = graph.nodes.get(node);
            boolean flopData = isSequentialDataPin(data);
            boolean constrainedOutput = data.outputPort
                    && constraints.getOutputDelaysPs().containsKey(node);
            if (!flopData && !data.outputPort) {
                continue;
            }
            report.endpoints.add(buildEndpoint(graph, constraints, clockAtPin,
                    arrivals, node, data, flopData, report));
        }
        summarize(report);
        return report;
    }

    private static TimingReport.EndpointResult buildEndpoint(
            TimingGraph graph, Constraints constraints, Map<String, String> clockAtPin,
            Map<String, List<Tag>> arrivals, String node, NodeData data,
            boolean flopData, TimingReport report) {
        TimingReport.EndpointResult result = new TimingReport.EndpointResult();
        result.endpoint = node;
        result.instance = data.instance;
        result.cell = data.cell == null ? null : data.cell.getName();
        result.endpointKind = flopData ? "flipflop_data_pin" : "output_port";

        String captureClock;
        double setupBase;
        double holdBase;
        if (flopData) {
            TimingArc checkArc = data.setupArc != null ? data.setupArc : data.holdArc;
            String clockPin = data.instance + "/" + checkArc.fromPin();
            captureClock = clockAtPin.get(clockPin);
            Clock clock = captureClock == null ? null
                    : constraints.getClocks().get(captureClock);
            if (clock == null) {
                setupBase = Double.NaN;
                holdBase = Double.NaN;
            } else {
                setupBase = round(clock.periodPs() - clock.uncertaintyPs());
                holdBase = round(clock.uncertaintyPs());
                if (data.setupArc != null) {
                    setupBase = round(setupBase - data.setupArc.checkPs());
                }
                if (data.holdArc != null) {
                    holdBase = round(holdBase + data.holdArc.checkPs());
                }
            }
        } else {
            String clockName = constraints.getPortDelayClock()
                    .get("set_output_delay:" + node);
            if (clockName == null) {
                captureClock = null;
                setupBase = Double.NaN;
                holdBase = Double.NaN;
            } else {
                captureClock = clockName;
                Clock clock = constraints.getClocks().get(clockName);
                double outputDelay = constraints.getOutputDelaysPs().get(node);
                setupBase = round(clock.periodPs() - clock.uncertaintyPs() - outputDelay);
                holdBase = round(clock.uncertaintyPs() + outputDelay);
            }
        }
        result.captureClock = captureClock;

        List<Tag> tags = arrivals.getOrDefault(node, List.of());
        for (Tag tag : tags) {
            if (tag.clock != null) {
                result.launchClocks.add(tag.clock);
            }
        }

        if (captureClock == null || tags.isEmpty()) {
            result.category = TimingReport.Category.UNCONSTRAINED;
            result.reason = captureClock == null
                    ? "endpoint has no capture clock"
                    : "no timing path reaches the endpoint";
            result.arrivalPs = tags.stream().mapToDouble(t -> t.arrivalPs).max().orElse(0);
            report.unconstrainedReasons.add(node + ": " + result.reason);
            return result;
        }
        // Exception matching happens per launch tag and takes precedence over both CDC
        // classification and default single-cycle checks.
        for (Tag tag : tags.stream().sorted(Comparator.comparingDouble((Tag t) -> t.arrivalPs)
                .reversed()).toList()) {
            List<String> pathNodes = new ArrayList<>();
            for (TimingReport.PathHop hop : tag.hops) {
                pathNodes.add(hop.node);
            }
            Decision decision = ExceptionResolver.resolve(constraints.getExceptions(),
                    tag.startNode, node, pathNodes);
            if (decision.falsePath) {
                result.category = TimingReport.Category.FALSE_PATH;
                result.appliedException = "false_path (line "
                        + decision.falsePathRule.line() + ")";
                result.arrivalPs = tag.arrivalPs;
                result.worstSetupPath = tag.hops;
                report.exceptionApplications.add(node + ": " + result.appliedException);
                return result;
            }
        }

        if (tags.stream().anyMatch(tag -> tag.clock == null)) {
            result.category = TimingReport.Category.UNCONSTRAINED;
            result.reason = "data path is launched from an unconstrained input (no clock "
                    + "and no set_input_delay clock)";
            result.arrivalPs = tags.stream().mapToDouble(t -> t.arrivalPs).max().orElse(0);
            report.unconstrainedReasons.add(node + ": " + result.reason);
            return result;
        }
        if (tags.stream().anyMatch(tag -> !tag.clock.equals(captureClock))) {
            result.category = TimingReport.Category.CROSS_CLOCK_DOMAIN;
            result.reason = "launch clock "
                    + tags.stream().map(t -> t.clock).distinct().toList()
                    + " differs from capture clock '" + captureClock
                    + "'; no single-period check is applied";
            result.arrivalPs = tags.stream().mapToDouble(t -> t.arrivalPs).max().orElse(0);
            return result;
        }

        Tag setupTag = tags.stream().max(Comparator.comparingDouble(t -> t.arrivalPs))
                .orElseThrow();
        Tag holdTag = tags.stream().min(Comparator.comparingDouble(t -> t.arrivalPs))
                .orElseThrow();
        List<String> pathNodes = new ArrayList<>();
        for (TimingReport.PathHop hop : setupTag.hops) {
            pathNodes.add(hop.node);
        }
        Decision decision = ExceptionResolver.resolve(constraints.getExceptions(),
                setupTag.startNode, node, pathNodes);

        int setupMulticycle = decision.setupCycles;
        int holdMulticycle = decision.holdCycles;
        if (decision.setupRule != null || decision.holdRule != null) {
            result.appliedException = "multicycle setup=" + setupMulticycle
                    + " hold=" + holdMulticycle + " (lines "
                    + (decision.setupRule == null ? "-" : decision.setupRule.line()) + "/"
                    + (decision.holdRule == null ? "-" : decision.holdRule.line()) + ")";
            report.exceptionApplications.add(node + ": " + result.appliedException);
        }

        double setupRequired = round(setupBase * setupMulticycle);
        double holdRequired = round(holdBase - (holdMulticycle == 0 ? 0
                : constraints.getClocks().get(captureClock).periodPs() * holdMulticycle));

        result.arrivalPs = setupTag.arrivalPs;
        result.setupRequiredPs = setupRequired;
        result.holdRequiredPs = holdRequired;
        result.setupSlackPs = round(setupRequired - setupTag.arrivalPs);
        result.holdSlackPs = round(holdTag.arrivalPs - holdRequired);
        result.worstSetupPath = setupTag.hops;
        result.worstHoldPath = holdTag.hops;
        result.category = (result.setupSlackPs < 0 || result.holdSlackPs < 0)
                ? TimingReport.Category.VIOLATED : TimingReport.Category.MET;
        return result;
    }

    private static void summarize(TimingReport report) {
        TimingReport.Summary summary = report.summary;
        summary.totalEndpoints = report.endpoints.size();
        summary.worstSetupSlackPs = Double.POSITIVE_INFINITY;
        summary.worstHoldSlackPs = Double.POSITIVE_INFINITY;
        for (TimingReport.EndpointResult endpoint : report.endpoints) {
            switch (endpoint.category) {
                case MET -> summary.met++;
                case VIOLATED -> {
                    summary.violated++;
                    if (endpoint.setupSlackPs < 0) {
                        summary.setupViolations++;
                    }
                    if (endpoint.holdSlackPs < 0) {
                        summary.holdViolations++;
                    }
                }
                case CROSS_CLOCK_DOMAIN -> summary.crossClockDomain++;
                case UNCONSTRAINED -> summary.unconstrained++;
                case FALSE_PATH -> summary.falsePaths++;
            }
            if (endpoint.setupSlackPs != null && endpoint.setupSlackPs < summary.worstSetupSlackPs) {
                summary.worstSetupSlackPs = endpoint.setupSlackPs;
                summary.worstSetupEndpoint = endpoint.endpoint;
            }
            if (endpoint.holdSlackPs != null && endpoint.holdSlackPs < summary.worstHoldSlackPs) {
                summary.worstHoldSlackPs = endpoint.holdSlackPs;
                summary.worstHoldEndpoint = endpoint.endpoint;
            }
        }
        if (summary.worstSetupEndpoint == null) {
            summary.worstSetupSlackPs = 0;
            summary.worstHoldSlackPs = 0;
        }
    }
}
