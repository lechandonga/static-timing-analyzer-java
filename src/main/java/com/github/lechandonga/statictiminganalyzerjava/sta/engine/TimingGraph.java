package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Cell;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Instance;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Library;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Net;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Netlist;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.PinRef;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.TimingArc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Elaborated timing graph plus strict cross-file reference validation.
 *
 * <p>Node names are canonical pin references ({@code instance/pin} or bare port names).
 * Data edges connect driver pins to load pins through nets (zero delay) and pins inside a
 * cell through combinational or clock-to-Q arcs (cell delay).
 */
final class TimingGraph {

    static final class NodeData {
        String instance;
        Cell cell;
        String localPin;
        boolean inputPort;
        boolean outputPort;
        TimingArc setupArc;
        TimingArc holdArc;
    }

    static final class Edge {
        final String to;
        final double delayPs;
        final String via;
        final boolean clockToQ;

        Edge(String to, double delayPs, String via, boolean clockToQ) {
            this.to = to;
            this.delayPs = delayPs;
            this.via = via;
            this.clockToQ = clockToQ;
        }
    }

    final Map<String, NodeData> nodes = new LinkedHashMap<>();
    final Map<String, List<Edge>> dataEdges = new LinkedHashMap<>();
    final Map<String, String> clockPinDriver = new LinkedHashMap<>();

    private TimingGraph() {
    }

    static TimingGraph build(Netlist netlist, Library library) {
        TimingGraph graph = new TimingGraph();

        for (String port : netlist.getInputPorts()) {
            NodeData data = new NodeData();
            data.inputPort = true;
            data.localPin = port;
            graph.nodes.put(port, data);
        }
        for (String port : netlist.getOutputPorts()) {
            if (graph.nodes.containsKey(port)) {
                throw new StaException(StaException.Code.FORMAT_ERROR,
                        "port '" + port + "' is declared both input and output");
            }
            NodeData data = new NodeData();
            data.outputPort = true;
            data.localPin = port;
            graph.nodes.put(port, data);
        }

        for (Instance instance : netlist.getInstances().values()) {
            Cell cell = library.getCells().get(instance.getCellName());
            if (cell == null) {
                throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                        "instance '" + instance.getName() + "' references unknown cell '"
                                + instance.getCellName() + "'");
            }
            for (String pin : cell.getPins()) {
                NodeData data = new NodeData();
                data.instance = instance.getName();
                data.cell = cell;
                data.localPin = pin;
                graph.nodes.put(PinRef.pin(instance.getName(), pin).canonical(), data);
            }
        }

        buildChecks(graph);

        java.util.Set<String> driverNetPairs = new java.util.LinkedHashSet<>();
        for (Net net : netlist.getNets().values()) {
            requireNode(graph, net.getDriver(), "driver of net '" + net.getName() + "'");
            String pair = net.getDriver().canonical() + "@" + net.getName();
            if (!driverNetPairs.add(pair)) {
                throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                        "net '" + net.getName() + "' lists driver '"
                                + net.getDriver().canonical() + "' twice");
            }
            for (PinRef load : net.getLoads()) {
                requireNode(graph, load, "load of net '" + net.getName() + "'");
            }
        }
        // The same (driver, load) pair appearing in two nets is ambiguous fanout.
        java.util.Set<String> driverLoadPairs = new java.util.LinkedHashSet<>();
        for (Net net : netlist.getNets().values()) {
            for (PinRef load : net.getLoads()) {
                String pair = net.getDriver().canonical() + "->" + load.canonical();
                if (!driverLoadPairs.add(pair)) {
                    throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                            "connection '" + pair + "' is declared in more than one net");
                }
            }
        }

        for (Net net : netlist.getNets().values()) {
            String driver = net.getDriver().canonical();
            for (PinRef loadRef : net.getLoads()) {
                String load = loadRef.canonical();
                if (load.equals(driver)) {
                    throw new StaException(StaException.Code.FORMAT_ERROR,
                            "net '" + net.getName() + "' connects '" + driver
                                    + "' to itself");
                }
                graph.dataEdges.computeIfAbsent(driver, k -> new ArrayList<>())
                        .add(new Edge(load, 0.0, "net:" + net.getName(), false));
            }
        }

        for (Instance instance : netlist.getInstances().values()) {
            Cell cell = library.getCells().get(instance.getCellName());
            for (String fromPin : cell.getPins()) {
                for (TimingArc arc : cell.arcsFrom(fromPin)) {
                    if (arc.type() == TimingArc.ArcType.SETUP
                            || arc.type() == TimingArc.ArcType.HOLD) {
                        continue;
                    }
                    String from = PinRef.pin(instance.getName(), fromPin).canonical();
                    String to = PinRef.pin(instance.getName(), arc.toPin()).canonical();
                    graph.dataEdges.computeIfAbsent(from, k -> new ArrayList<>())
                            .add(new Edge(to, arc.delayPs(),
                                    "arc:" + cell.getName() + "/" + fromPin + "->" + arc.toPin(),
                                    arc.type() == TimingArc.ArcType.CLOCK_TO_Q));
                }
            }
        }

        validateConnectivity(graph);
        return graph;
    }

    private static void buildChecks(TimingGraph graph) {
        for (Map.Entry<String, NodeData> entry : graph.nodes.entrySet()) {
            NodeData data = entry.getValue();
            if (data.cell == null) {
                continue;
            }
            for (String pin : data.cell.getPins()) {
                TimingArc setup = data.cell.findArc(pin, data.localPin,
                        TimingArc.ArcType.SETUP);
                TimingArc hold = data.cell.findArc(pin, data.localPin,
                        TimingArc.ArcType.HOLD);
                if (setup != null) {
                    data.setupArc = setup;
                }
                if (hold != null) {
                    data.holdArc = hold;
                }
            }
        }
    }

    private static void requireNode(TimingGraph graph, PinRef ref, String role) {
        if (!graph.nodes.containsKey(ref.canonical())) {
            throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                    role + " references unknown pin '" + ref.canonical() + "'");
        }
    }

    private static void validateConnectivity(TimingGraph graph) {
        for (String node : graph.nodes.keySet()) {
            NodeData data = graph.nodes.get(node);
            boolean hasIncoming = graph.dataEdges.values().stream()
                    .flatMap(List::stream).anyMatch(edge -> edge.to.equals(node));
            boolean hasOutgoing = graph.dataEdges.containsKey(node)
                    && !graph.dataEdges.get(node).isEmpty();
            if (!hasIncoming && !data.inputPort) {
                throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                        "pin '" + node + "' has no driving net");
            }
            if (!hasOutgoing && !data.outputPort && data.setupArc == null
                    && data.holdArc == null) {
                throw new StaException(StaException.Code.UNKNOWN_REFERENCE,
                        "pin '" + node + "' is not connected to any load or check");
            }
            if (data.inputPort && hasIncoming) {
                throw new StaException(StaException.Code.FORMAT_ERROR,
                        "input port '" + node + "' must not be driven by a net");
            }
            if (data.outputPort && hasOutgoing) {
                throw new StaException(StaException.Code.FORMAT_ERROR,
                        "output port '" + node + "' must not drive a net");
            }
        }
    }
}
