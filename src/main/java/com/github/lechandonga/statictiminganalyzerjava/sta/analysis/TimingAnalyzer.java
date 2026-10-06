package com.github.lechandonga.statictiminganalyzerjava.sta.analysis;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.CellLibrary;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Constraints;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Netlist;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 静态时序分析核心。计算规则与例外优先级见 docs/timing-rules.md。
 * 基于有向图迭代算法；组合环用 Tarjan SCC 定位，不使用无界递归。
 */
public class TimingAnalyzer {

    private static final double EPS = 1e-9;

    private record Arc(String target, double delay, String cell) {
    }

    private final CellLibrary lib;
    private final Netlist netlist;
    private final Constraints constraints;

    private final Map<String, List<Arc>> dataGraph = new LinkedHashMap<>();
    private final Map<String, String> clockDomain = new HashMap<>();
    private final Set<String> startpoints = new LinkedHashSet<>();
    private final Set<String> endpoints = new LinkedHashSet<>();
    private final Map<String, CellLibrary.CellType> seqInstances = new LinkedHashMap<>();

    public TimingAnalyzer(CellLibrary lib, Netlist netlist, Constraints constraints) {
        this.lib = lib;
        this.netlist = netlist;
        this.constraints = constraints;
    }

    public TimingReport analyze() {
        validateReferences();
        propagateClocks();
        buildDataGraph();
        validateNewConstraintRefs();

        List<List<String>> cycles = findCycles();
        if (!cycles.isEmpty()) {
            TimingReport report = new TimingReport();
            report.status = TimingReport.Status.COMBINATIONAL_CYCLE;
            for (List<String> cyc : cycles) {
                TimingReport.Cycle c = new TimingReport.Cycle();
                c.nodes = cyc;
                c.description = "组合环: " + String.join(" -> ", cyc) + " -> " + cyc.get(0);
                report.cycles.add(c);
            }
            report.cycles.sort(Comparator.comparing(a -> String.join("|", a.nodes)));
            return report;
        }

        List<String> topo = topologicalOrder();
        Map<String, TimingReport.EndpointResult> byEndpoint = new LinkedHashMap<>();
        for (String ep : endpoints) {
            TimingReport.EndpointResult er = new TimingReport.EndpointResult();
            er.endpoint = ep;
            er.cell = endpointCell(ep);
            byEndpoint.put(ep, er);
        }
        List<String> starts = new ArrayList<>(startpoints);
        Collections.sort(starts);
        for (String start : starts) {
            analyzeFromStartpoint(start, topo, byEndpoint, false);
            analyzeFromStartpoint(start, topo, byEndpoint, true);
        }
        TimingReport report = new TimingReport();
        List<TimingReport.EndpointResult> ers = new ArrayList<>(byEndpoint.values());
        Collections.sort(ers);
        for (TimingReport.EndpointResult er : ers) {
            finalizeEndpoint(er);
            report.endpoints.add(er);
        }
        buildSummary(report);
        return report;
    }

    // ---------------------------------------------------------------
    // 引用校验
    // ---------------------------------------------------------------

    private void validateReferences() {
        Set<String> pinNames = new HashSet<>();
        for (Netlist.Instance inst : netlist.instances().values()) {
            for (String pin : inst.pinNet.keySet()) {
                pinNames.add(inst.name + "/" + pin);
            }
        }
        for (Constraints.Clock clk : constraints.clocks().values()) {
            if (!netlist.primaryInputs().contains(clk.name)) {
                throw new StaException("CLOCK_SOURCE_NOT_FOUND", "constraints", 0,
                        "时钟 " + clk.name + " 的源点必须是网表中声明的主输入");
            }
        }
        constraints.inputDelays().forEach((port, io) -> {
            if (!netlist.primaryInputs().contains(port)) {
                throw new StaException("PORT_NOT_FOUND", "constraints", 0,
                        "set_input_delay 引用了不存在的主输入: " + port);
            }
            if (!constraints.clocks().containsKey(io.clock())) {
                throw new StaException("CLOCK_NOT_FOUND", "constraints", 0,
                        "端口 " + port + " 的输入延时引用了未定义时钟: " + io.clock());
            }
        });
        constraints.outputDelays().forEach((port, io) -> {
            if (!netlist.primaryOutputs().contains(port)) {
                throw new StaException("PORT_NOT_FOUND", "constraints", 0,
                        "set_output_delay 引用了不存在的主输出: " + port);
            }
            if (!constraints.clocks().containsKey(io.clock())) {
                throw new StaException("CLOCK_NOT_FOUND", "constraints", 0,
                        "端口 " + port + " 的输出延时引用了未定义时钟: " + io.clock());
            }
        });
        for (Constraints.ExceptionRule rule : constraints.exceptions()) {
            validateExceptionRef(rule.from(), pinNames);
            validateExceptionRef(rule.to(), pinNames);
        }
    }

    /** 不确定度与降额的引用在三份文件全部解析后统一校验，结论与声明顺序无关。 */
    private void validateNewConstraintRefs() {
        for (Constraints.UncertaintyRule u : constraints.pathUncertainty()) {
            validateExceptionRef(u.from());
            validateExceptionRef(u.to());
        }
        for (String clock : constraints.clockUncertainty().keySet()) {
            if (!constraints.clocks().containsKey(clock)) {
                throw new StaException("UNCERTAINTY_CLOCK_NOT_FOUND", "constraints", 0,
                        "set_clock_uncertainty 引用了未定义时钟: " + clock);
            }
        }
        for (Constraints.DerateRule d : constraints.derates()) {
            if ("CELL".equals(d.scope) && !lib.cells().containsKey(d.target)) {
                throw new StaException("DERATE_CELL_NOT_FOUND", "constraints", 0,
                        "set_timing_derate -cell 引用了库中不存在的单元类型: " + d.target);
            }
            if ("INSTANCE".equals(d.scope) && !netlist.instances().containsKey(d.target)) {
                throw new StaException("DERATE_INSTANCE_NOT_FOUND", "constraints", 0,
                        "set_timing_derate -instance 引用了网表中不存在的实例: " + d.target);
            }
        }
    }

    private void validateExceptionRef(String ref) {
        Set<String> pinNames = new HashSet<>();
        for (Netlist.Instance inst : netlist.instances().values()) {
            for (String pin : inst.pinNet.keySet()) {
                pinNames.add(inst.name + "/" + pin);
            }
        }
        validateExceptionRef(ref, pinNames);
    }

    private void validateExceptionRef(String ref, Set<String> pins) {
        boolean ok = constraints.clocks().containsKey(ref)
                || netlist.primaryInputs().contains(ref)
                || netlist.primaryOutputs().contains(ref)
                || pins.contains(ref);
        if (!ok) {
            throw new StaException("EXCEPTION_REF_NOT_FOUND", "constraints", 0,
                    "例外引用既不是时钟也不是端口/单元引脚: " + ref);
        }
    }

    // ---------------------------------------------------------------
    // 时钟传播：从时钟主输入沿组合单元传播（理想时钟，网络延时 0）
    // ---------------------------------------------------------------

    private void propagateClocks() {
        Map<String, String> netClock = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String clk : constraints.clocks().keySet()) {
            netClock.put(clk, clk);
            queue.add(clk);
        }
        while (!queue.isEmpty()) {
            String net = queue.poll();
            String label = netClock.get(net);
            for (String reader : sortedCopy(netlist.nets().get(net).readers)) {
                String[] ip = splitPin(reader);
                Netlist.Instance inst = netlist.instances().get(ip[0]);
                CellLibrary.CellType ct = lib.cells().get(inst.cellType);
                if (ct.sequential) {
                    if (ip[1].equals(ct.clkPin)) {
                        tag(clockDomain, pinNode(inst.name, ip[1]), label);
                    }
                    continue;
                }
                for (String arc : new ArrayList<>(ct.delays.keySet())) {
                    String inPin = arc.split("->")[0];
                    String outPin = arc.split("->")[1];
                    if (!ip[1].equals(inPin)) {
                        continue;
                    }
                    String outNet = inst.pinNet.get(outPin);
                    tag(netClock, outNet, label);
                    tag(clockDomain, pinNode(inst.name, outPin), label);
                    queue.add(outNet);
                }
            }
        }
    }

    private void tag(Map<String, String> map, String key, String label) {
        String old = map.put(key, label);
        if (old != null && !old.equals(label)) {
            throw new StaException("CLOCK_CONFLICT", "netlist", 0,
                    "时钟网络在 " + key + " 处汇合了不同时钟: " + old + " 与 " + label);
        }
    }

    // ---------------------------------------------------------------
    // 数据图
    // ---------------------------------------------------------------

    private void buildDataGraph() {
        for (Netlist.Instance inst : netlist.instances().values()) {
            CellLibrary.CellType ct = lib.cells().get(inst.cellType);
            if (ct.sequential) {
                seqInstances.put(inst.name, ct);
                String q = pinNode(inst.name, ct.dataOutPin);
                dataGraph.computeIfAbsent(q, k -> new ArrayList<>());
                startpoints.add(q);
                String d = pinNode(inst.name, ct.dataInPin);
                dataGraph.computeIfAbsent(d, k -> new ArrayList<>());
                endpoints.add(d);
            } else {
                for (Map.Entry<String, Double> e : ct.delays.entrySet()) {
                    String[] ft = e.getKey().split("->");
                    String in = pinNode(inst.name, ft[0]);
                    String out = pinNode(inst.name, ft[1]);
                    if (clockDomain.containsKey(out)) {
                        continue;
                    }
                    dataGraph.computeIfAbsent(in, k -> new ArrayList<>())
                            .add(new Arc(out, e.getValue(), inst.name));
                    dataGraph.computeIfAbsent(out, k -> new ArrayList<>());
                }
            }
        }
        List<String> netNames = new ArrayList<>(netlist.nets().keySet());
        Collections.sort(netNames);
        for (String netName : netNames) {
            Netlist.Net net = netlist.nets().get(netName);
            boolean clockNet = isClockNet(netName);
            String src;
            if (netlist.primaryInputs().contains(netName)) {
                src = portNode(netName);
                dataGraph.computeIfAbsent(src, k -> new ArrayList<>());
                if (!clockNet) {
                    startpoints.add(src);
                }
            } else {
                src = pinNode(net.driver);
                dataGraph.computeIfAbsent(src, k -> new ArrayList<>());
            }
            if (netlist.primaryOutputs().contains(netName)) {
                String p = portNode(netName);
                dataGraph.computeIfAbsent(p, k -> new ArrayList<>());
                if (!clockNet) {
                    dataGraph.get(src).add(new Arc(p, 0, null));
                    endpoints.add(p);
                }
                continue;
            }
            for (String reader : sortedCopy(net.readers)) {
                String[] ip = splitPin(reader);
                Netlist.Instance inst = netlist.instances().get(ip[0]);
                CellLibrary.CellType ct = lib.cells().get(inst.cellType);
                if (ct.sequential && ip[1].equals(ct.clkPin)) {
                    continue;
                }
                if (!ct.sequential && clockDomain.containsKey(pinNode(inst.name, ip[1]))) {
                    continue;
                }
                if (clockNet && netlist.primaryInputs().contains(netName)) {
                    continue;
                }
                dataGraph.get(src).add(new Arc(pinNode(inst.name, ip[1]), 0, null));
            }
        }
    }

    private boolean isClockNet(String netName) {
        if (constraints.clocks().containsKey(netName) && netlist.primaryInputs().contains(netName)) {
            return true;
        }
        Netlist.Net net = netlist.nets().get(netName);
        return net != null && net.driver != null
                && clockDomain.containsKey(pinNode(net.driver));
    }

    // ---------------------------------------------------------------
    // 组合环检测（迭代 Tarjan）
    // ---------------------------------------------------------------

    private List<List<String>> findCycles() {
        List<List<String>> result = new ArrayList<>();
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Boolean> onStack = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        int[] counter = {0};
        List<String> roots = new ArrayList<>(dataGraph.keySet());
        Collections.sort(roots);
        for (String root : roots) {
            if (index.containsKey(root)) {
                continue;
            }
            Deque<Object[]> work = new ArrayDeque<>();
            work.push(new Object[]{root, 0});
            while (!work.isEmpty()) {
                Object[] frame = work.peek();
                String v = (String) frame[0];
                int pi = (int) frame[1];
                if (pi == 0) {
                    index.put(v, counter[0]);
                    low.put(v, counter[0]);
                    counter[0]++;
                    stack.push(v);
                    onStack.put(v, true);
                }
                List<Arc> arcs = dataGraph.getOrDefault(v, List.of());
                if (pi < arcs.size()) {
                    frame[1] = pi + 1;
                    String w = arcs.get(pi).target();
                    if (!index.containsKey(w)) {
                        work.push(new Object[]{w, 0});
                    } else if (Boolean.TRUE.equals(onStack.get(w))) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    boolean isSccRoot = low.get(v).equals(index.get(v));
                    List<String> scc = null;
                    if (isSccRoot) {
                        scc = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.put(w, false);
                            scc.add(w);
                        } while (!w.equals(v));
                    }
                    work.pop();
                    if (!work.isEmpty()) {
                        String parent = (String) work.peek()[0];
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                    if (isSccRoot) {
                        boolean selfLoop = arcs.stream().anyMatch(a -> a.target().equals(v));
                        if (scc.size() > 1 || selfLoop) {
                            result.add(orderedCycle(scc));
                        }
                    }
                }
            }
        }
        return result;
    }

    private List<String> orderedCycle(List<String> scc) {
        Set<String> in = new HashSet<>(scc);
        List<String> order = new ArrayList<>();
        String start = scc.stream().sorted().findFirst().orElseThrow();
        String cur = start;
        Set<String> used = new HashSet<>();
        do {
            order.add(cur);
            used.add(cur);
            String next = null;
            for (Arc a : dataGraph.getOrDefault(cur, List.of())) {
                if (in.contains(a.target()) && !used.contains(a.target())) {
                    next = a.target();
                    break;
                }
            }
            if (next == null) {
                break;
            }
            cur = next;
        } while (!cur.equals(start));
        Collections.sort(order);
        return order;
    }

    // ---------------------------------------------------------------
    // 拓扑排序（Kahn）；到达此处时已确认无环
    // ---------------------------------------------------------------

    private List<String> topologicalOrder() {
        Map<String, Integer> indeg = new HashMap<>();
        dataGraph.keySet().forEach(n -> indeg.put(n, 0));
        for (List<Arc> arcs : dataGraph.values()) {
            for (Arc a : arcs) {
                indeg.merge(a.target(), 1, Integer::sum);
            }
        }
        // 用有序队列保证同名图拓扑序唯一
        java.util.PriorityQueue<String> pq = new java.util.PriorityQueue<>();
        indeg.forEach((n, d) -> {
            if (d == 0) {
                pq.add(n);
            }
        });
        List<String> order = new ArrayList<>();
        while (!pq.isEmpty()) {
            String n = pq.poll();
            order.add(n);
            for (Arc a : dataGraph.getOrDefault(n, List.of())) {
                int d = indeg.merge(a.target(), -1, Integer::sum);
                if (d == 0) {
                    pq.add(a.target());
                }
            }
        }
        return order;
    }

    // ---------------------------------------------------------------
    // 单起点传播与端点检查
    // ---------------------------------------------------------------

    private void analyzeFromStartpoint(String start, List<String> topo,
                                       Map<String, TimingReport.EndpointResult> byEndpoint,
                                       boolean early) {
        Map<String, Double> arrival = new HashMap<>();
        Map<String, String> predecessor = new HashMap<>();
        Map<String, String> factorTag = new HashMap<>();
        StartInfo si = startInfo(start);
        double startFactor = startDerateFactor(start, early);
        arrival.put(start, si.arrival * startFactor);
        factorTag.put(start, factorDescription(startCellName(start), startCellType(start), startFactor, early));
        for (String node : topo) {
            if (!arrival.containsKey(node)) {
                continue;
            }
            if (byEndpoint.containsKey(node)) {
                continue;
            }
            double at = arrival.get(node);
            for (Arc a : dataGraph.getOrDefault(node, List.of())) {
                double factor = a.cell() == null ? 1.0 : cellFactor(a.cell(), early);
                double cand = at + a.delay() * factor;
                Double old = arrival.get(a.target());
                boolean better = old != null && (early ? cand < old - EPS : cand > old + EPS);
                boolean tie = old != null && Math.abs(cand - old) <= EPS;
                if (old == null || better
                        || (tie && betterPredecessor(a.target(), node, predecessor))) {
                    arrival.put(a.target(), cand);
                    predecessor.put(a.target(), node);
                    factorTag.put(a.target(), a.cell() != null
                            ? factorDescription(a.cell(), netlist.instances().get(a.cell()).cellType,
                            factor, early)
                            : "(net)*1.000");
                }
            }
        }
        List<String> eps = new ArrayList<>(endpoints);
        Collections.sort(eps);
        for (String ep : eps) {
            Double at = arrival.get(ep);
            if (at == null) {
                continue;
            }
            TimingReport.PathPair pair = obtainPair(byEndpoint.get(ep), start, ep);
            List<String> path = tracePath(start, ep, predecessor);
            List<String> derates = collectDerates(start, ep, predecessor, factorTag);
            if (early) {
                pair.holdArrival = round(at);
                pair.holdDataPath = path;
                pair.holdDerates = derates;
            } else {
                pair.arrival = round(at);
                pair.dataPath = path;
                pair.setupDerates = derates;
            }
        }
    }

    /** 同一 (起点, 终点) 的晚角/早角两次传播共享同一条 PathPair。 */
    private TimingReport.PathPair obtainPair(TimingReport.EndpointResult er, String start, String endpoint) {
        String shownStart = display(start);
        for (TimingReport.PathPair existing : er.pairs) {
            if (existing.startpoint.equals(shownStart)) {
                return existing;
            }
        }
        TimingReport.PathPair pair = new TimingReport.PathPair();
        pair.startpoint = shownStart;
        StartInfo si = startInfo(start);
        pair.launchClock = si.launchClock;
        pair.captureClock = endpointInfo(endpoint).captureClock;
        classifyAndCheckPair(pair, start, si, endpoint);
        er.pairs.add(pair);
        return pair;
    }

    private List<String> collectDerates(String start, String endpoint, Map<String, String> pred,
                                        Map<String, String> factorTag) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String startTag = factorTag.get(start);
        if (startTag != null) {
            ordered.add(startTag);
        }
        String cur = endpoint;
        while (cur != null && !cur.equals(start)) {
            String tag = factorTag.get(cur);
            if (tag != null && !tag.startsWith("(net)")) {
                ordered.add(tag);
            }
            cur = pred.get(cur);
        }
        List<String> result = new ArrayList<>(ordered);
        Collections.sort(result);
        return result;
    }

    private boolean betterPredecessor(String target, String candidateNode,
                                      Map<String, String> predecessor) {
        String cur = predecessor.get(target);
        return cur == null || candidateNode.compareTo(cur) < 0;
    }

    private record StartInfo(String launchClock, double arrival) {
    }

    private StartInfo startInfo(String node) {
        if (node.startsWith("port:")) {
            String port = node.substring(5);
            Constraints.IoDelay io = constraints.inputDelays().get(port);
            if (io != null) {
                return new StartInfo(io.clock(), io.delayNs());
            }
            return new StartInfo(null, 0);
        }
        String[] ip = splitPinNode(node);
        CellLibrary.CellType ct = seqInstances.get(ip[0]);
        return new StartInfo(clockDomain.get(pinNode(ip[0], ct.clkPin)), ct.clkToQ);
    }

    private String startCellName(String node) {
        return node.startsWith("port:") ? null : splitPinNode(node)[0];
    }

    private String startCellType(String node) {
        return node.startsWith("port:") ? null
                : netlist.instances().get(splitPinNode(node)[0]).cellType;
    }

    // ---------------------------------------------------------------
    // 时序降额：实例覆盖单元、单元覆盖全局；早/晚角独立，缺角用另一角
    // ---------------------------------------------------------------

    private double cellFactor(String instName, boolean early) {
        String cellType = netlist.instances().get(instName).cellType;
        double global = 1.0;
        double byCell = Double.NaN;
        double byInstance = Double.NaN;
        for (Constraints.DerateRule d : constraints.derates()) {
            double f = early ? d.earlyFactor : d.lateFactor;
            switch (d.scope) {
                case "GLOBAL" -> global = f;
                case "CELL" -> {
                    if (d.target.equals(cellType)) {
                        byCell = f;
                    }
                }
                default -> {
                    if (d.target.equals(instName)) {
                        byInstance = f;
                    }
                }
            }
        }
        if (!Double.isNaN(byInstance)) {
            return byInstance;
        }
        if (!Double.isNaN(byCell)) {
            return byCell;
        }
        return global;
    }

    /** 起点降额：仅寄存器 Q 脚的 clk->q 跟随对应角；主输入的 input delay 是片外数据，不降额。 */
    private double startDerateFactor(String node, boolean early) {
        return node.startsWith("port:") ? 1.0 : cellFactor(splitPinNode(node)[0], early);
    }

    private String factorDescription(String instName, String cellType, double factor, boolean early) {
        if (instName == null) {
            // input delay 属片外数据，不参与降额；标签固定为 1.000(unrated)，不显示 global。
            return String.format(java.util.Locale.ROOT,
                    "(input_port)*1.000(%s:unrated)", early ? "early" : "late");
        }
        String scope = "global";
        String key = null;
        for (Constraints.DerateRule d : constraints.derates()) {
            switch (d.scope) {
                case "GLOBAL" -> {
                    key = null;
                    scope = "global";
                }
                case "CELL" -> {
                    if (d.target.equals(cellType)) {
                        key = d.target;
                        scope = "cell";
                    }
                }
                default -> {
                    if (d.target.equals(instName)) {
                        key = d.target;
                        scope = "instance";
                    }
                }
            }
        }
        String who = instName + "[" + cellType + "]";
        return String.format(java.util.Locale.ROOT, "%s*%.3f(%s:%s%s)",
                who, factor, early ? "early" : "late", scope, key != null ? ":" + key : "");
    }

    // ---------------------------------------------------------------
    // 时钟不确定度：路径级 > 时钟级 > 全局；同粒度冲突确定性报错
    // ---------------------------------------------------------------

    private record UncertaintyDecision(double value, String source) {
    }

    private UncertaintyDecision decideUncertainty(String launchClock, String start,
                                                  String captureClock, String endpoint) {
        List<Constraints.UncertaintyRule> applicable = new ArrayList<>();
        Map<Constraints.UncertaintyRule, int[]> specs = new HashMap<>();
        for (Constraints.UncertaintyRule r : constraints.pathUncertainty()) {
            int fs = refMatch(r.from(), launchClock, start);
            int ts = refMatch(r.to(), captureClock, endpoint);
            if (fs > 0 && ts > 0) {
                applicable.add(r);
                specs.put(r, new int[]{fs, ts});
            }
        }
        if (!applicable.isEmpty()) {
            int maxFrom = applicable.stream().mapToInt(r -> specs.get(r)[0]).max().orElse(0);
            int maxTo = applicable.stream().mapToInt(r -> specs.get(r)[1]).max().orElse(0);
            List<Constraints.UncertaintyRule> best = applicable.stream()
                    .filter(r -> specs.get(r)[0] == maxFrom && specs.get(r)[1] == maxTo)
                    .sorted(Comparator.comparing(Constraints.UncertaintyRule::from)
                            .thenComparing(Constraints.UncertaintyRule::to))
                    .toList();
            long distinct = best.stream()
                    .mapToDouble(Constraints.UncertaintyRule::valueNs).distinct().count();
            if (distinct > 1) {
                Constraints.UncertaintyRule first = best.get(0);
                throw new StaException("UNCERTAINTY_CONFLICT", "constraints", 0,
                        "同粒度的多条路径级 set_clock_uncertainty 作用于同一条路径且取值不同: "
                                + first.from() + " -> " + first.to());
            }
            Constraints.UncertaintyRule chosen = best.get(0);
            return new UncertaintyDecision(chosen.valueNs(),
                    "path(" + chosen.from() + "->" + chosen.to() + ")");
        }
        double clockValue = Double.NaN;
        Set<String> owners = new LinkedHashSet<>();
        for (String clk : List.of(launchClock, captureClock)) {
            if (clk != null && constraints.clockUncertainty().containsKey(clk)) {
                double v = constraints.clockUncertainty().get(clk);
                if (Double.isNaN(clockValue) || v > clockValue) {
                    clockValue = v;
                }
                owners.add(clk);
            }
        }
        if (!Double.isNaN(clockValue)) {
            return new UncertaintyDecision(clockValue,
                    "clock(" + String.join(",", new java.util.TreeSet<>(owners)) + ")");
        }
        if (constraints.globalUncertainty() != null) {
            return new UncertaintyDecision(constraints.globalUncertainty(), "global");
        }
        return new UncertaintyDecision(0, "(none)");
    }

    private void classifyAndCheckPair(TimingReport.PathPair pair, String start,
                                      StartInfo si, String endpoint) {
        ExceptionDecision ex = decideException(si.launchClock, start, endpoint);
        EndpointInfo ei = endpointInfo(endpoint);

        if (ex.rule != null && "FALSE_PATH".equals(ex.rule.kind())) {
            pair.category = TimingReport.PairCategory.FALSE_PATH;
            pair.exception = "FALSE_PATH" + ex.matchedBy;
            return;
        }

        boolean cdc = si.launchClock != null && ei.captureClock != null
                && !si.launchClock.equals(ei.captureClock);
        if (cdc && (ex.rule == null || !"MULTICYCLE".equals(ex.rule.kind()))) {
            pair.category = TimingReport.PairCategory.CDC;
            return;
        }
        if (si.launchClock == null || ei.captureClock == null) {
            pair.category = TimingReport.PairCategory.UNCONSTRAINED;
            return;
        }

        pair.category = TimingReport.PairCategory.CONSTRAINED;
        Constraints.Clock launch = constraints.clocks().get(si.launchClock);
        Constraints.Clock capture = constraints.clocks().get(ei.captureClock);
        int nSetup = 1;
        int nHold = 0;
        if (ex.rule != null) {
            nSetup = ex.rule.multiplier();
            nHold = ex.rule.multiplier() - 1;
            pair.exception = "MULTICYCLE x" + ex.rule.multiplier() + ex.matchedBy;
        }
        UncertaintyDecision uSetup = decideUncertainty(si.launchClock, start,
                ei.captureClock, endpoint);
        // 保持与建立使用同一份生效不确定度，但落点方向相反（setup 扣减、hold 加回）。
        pair.setupUncertainty = round(uSetup.value);
        pair.holdUncertainty = round(uSetup.value);
        pair.setupUncertaintySource = uSetup.source;
        pair.holdUncertaintySource = uSetup.source;
        double setupRequired;
        double holdRequired;
        if (cdc) {
            setupRequired = nSetup * capture.periodNs - ei.extraDelay - ei.setupTime
                    - uSetup.value;
            holdRequired = (double) nHold * capture.periodNs + ei.extraDelay + ei.holdTime
                    + uSetup.value;
        } else {
            setupRequired = nSetup * launch.periodNs - ei.extraDelay - ei.setupTime
                    - uSetup.value;
            holdRequired = (double) nHold * launch.periodNs + ei.extraDelay + ei.holdTime
                    + uSetup.value;
        }
        pair.setupRequired = round(setupRequired);
        pair.holdRequired = round(holdRequired);
        pair.setupSlack = null;
        pair.holdSlack = null;
    }

    private record EndpointInfo(String captureClock, double extraDelay, double setupTime, double holdTime) {
    }

    private EndpointInfo endpointInfo(String node) {
        if (node.startsWith("port:")) {
            String port = node.substring(5);
            Constraints.IoDelay io = constraints.outputDelays().get(port);
            if (io == null) {
                return new EndpointInfo(null, 0, 0, 0);
            }
            return new EndpointInfo(io.clock(), io.delayNs(), 0, 0);
        }
        String[] ip = splitPinNode(node);
        CellLibrary.CellType ct = seqInstances.get(ip[0]);
        String clk = clockDomain.get(pinNode(ip[0], ct.clkPin));
        return new EndpointInfo(clk, 0, ct.setup, ct.hold);
    }

    private String endpointCell(String node) {
        if (node.startsWith("port:")) {
            return "(output port)";
        }
        String[] ip = splitPinNode(node);
        return ip[0] + " [" + netlist.instances().get(ip[0]).cellType + "]";
    }

    private List<String> tracePath(String start, String endpoint, Map<String, String> pred) {
        LinkedListWrap path = new LinkedListWrap();
        String cur = endpoint;
        while (cur != null) {
            path.addFirst(display(cur));
            if (cur.equals(start)) {
                break;
            }
            cur = pred.get(cur);
        }
        return path.list;
    }

    private static final class LinkedListWrap {
        final List<String> list = new ArrayList<>();

        void addFirst(String s) {
            list.add(0, s);
        }
    }

    // ---------------------------------------------------------------
    // 例外匹配与冲突仲裁（规则见 docs/timing-rules.md）
    // ---------------------------------------------------------------

    private record ExceptionDecision(Constraints.ExceptionRule rule, String matchedBy) {
    }

    private ExceptionDecision decideException(String launchClock, String start, String endpoint) {
        String captureClock = endpointInfo(endpoint).captureClock;
        List<Constraints.ExceptionRule> applicable = new ArrayList<>();
        Map<Constraints.ExceptionRule, int[]> specs = new HashMap<>();
        for (Constraints.ExceptionRule r : constraints.exceptions()) {
            int fs = refMatch(r.from(), launchClock, start);
            int ts = refMatch(r.to(), captureClock, endpoint);
            if (fs > 0 && ts > 0) {
                applicable.add(r);
                specs.put(r, new int[]{fs, ts});
            }
        }
        if (applicable.isEmpty()) {
            return new ExceptionDecision(null, "");
        }
        int maxFrom = applicable.stream().mapToInt(r -> specs.get(r)[0]).max().orElse(0);
        int maxTo = applicable.stream().mapToInt(r -> specs.get(r)[1]).max().orElse(0);
        List<Constraints.ExceptionRule> best = applicable.stream()
                .filter(r -> specs.get(r)[0] == maxFrom && specs.get(r)[1] == maxTo)
                .toList();
        Constraints.ExceptionRule chosen = best.get(0);
        boolean hasFalse = best.stream().anyMatch(r -> r.kind().equals("FALSE_PATH"));
        boolean hasMulti = best.stream().anyMatch(r -> r.kind().equals("MULTICYCLE"));
        if (hasFalse) {
            chosen = best.stream().filter(r -> r.kind().equals("FALSE_PATH")).findFirst().orElseThrow();
            if (hasMulti) {
                throw new StaException("EXCEPTION_CONFLICT", "constraints", 0,
                        "同一对起点/终点 (" + chosen.from() + " -> " + chosen.to()
                                + ") 同时被伪路径与多周期路径覆盖，按最高优先级采用伪路径仍属冲突，请删除其一");
            }
        }
        long distinctMultipliers = best.stream()
                .filter(r -> r.kind().equals("MULTICYCLE"))
                .mapToInt(Constraints.ExceptionRule::multiplier).distinct().count();
        if (distinctMultipliers > 1) {
            throw new StaException("EXCEPTION_CONFLICT", "constraints", 0,
                    "同粒度的多个 set_multicycle_path 作用于同一对起点/终点且倍数不同: "
                            + chosen.from() + " -> " + chosen.to());
        }
        String by = "(from=" + chosen.from() + ",to=" + chosen.to() + ")";
        return new ExceptionDecision(chosen, by);
    }

    /** 引用匹配分值：0 不匹配；1 时钟/端口粒度；2 单元引脚粒度。 */
    private int refMatch(String ref, String clock, String node) {
        String shown = display(node);
        boolean pinLevel = !node.startsWith("port:") && shown.contains("/");
        if (ref.equals(shown)) {
            return pinLevel ? 2 : 1;
        }
        if (clock != null && ref.equals(clock)) {
            return 1;
        }
        return 0;
    }

    // ---------------------------------------------------------------
    // 端点结论汇总
    // ---------------------------------------------------------------

    private void finalizeEndpoint(TimingReport.EndpointResult er) {
        for (TimingReport.PathPair p : er.pairs) {
            if (p.category != TimingReport.PairCategory.CONSTRAINED) {
                continue;
            }
            // 双角传播均完成后统一结算：晚角 arrival 对建立，早角 arrival 对保持。
            p.setupSlack = round(p.setupRequired - p.arrival);
            p.holdSlack = round(p.holdArrival - p.holdRequired);
        }
        List<TimingReport.PathPair> constrained = er.pairs.stream()
                .filter(p -> p.category == TimingReport.PairCategory.CONSTRAINED).toList();
        int falsePaths = 0;
        int cdcPairs = 0;
        int uncovered = 0;
        for (TimingReport.PathPair p : er.pairs) {
            switch (p.category) {
                case FALSE_PATH -> falsePaths++;
                case CDC -> cdcPairs++;
                case UNCONSTRAINED -> uncovered++;
                default -> {
                }
            }
        }
        if (!constrained.isEmpty()) {
            TimingReport.PathPair worstSetup = constrained.stream()
                    .min(Comparator.comparingDouble((TimingReport.PathPair p) -> p.setupSlack)
                            .thenComparing(p -> p.startpoint)).orElseThrow();
            TimingReport.PathPair worstHold = constrained.stream()
                    .min(Comparator.comparingDouble((TimingReport.PathPair p) -> p.holdSlack)
                            .thenComparing(p -> p.startpoint)).orElseThrow();
            er.worstSetupSlack = worstSetup.setupSlack;
            er.worstHoldSlack = worstHold.holdSlack;
            er.worstSetupPair = worstSetup;
            er.worstHoldPair = worstHold;
            er.worstSetupPath = worstSetup.dataPath;
            er.worstHoldPath = worstHold.holdDataPath;
            boolean sv = worstSetup.setupSlack < -EPS;
            boolean hv = worstHold.holdSlack < -EPS;
            er.status = sv && hv ? TimingReport.EndpointStatus.SETUP_AND_HOLD_VIOLATED
                    : sv ? TimingReport.EndpointStatus.SETUP_VIOLATED
                    : hv ? TimingReport.EndpointStatus.HOLD_VIOLATED
                    : TimingReport.EndpointStatus.MET;
        } else if (cdcPairs > 0 && uncovered == 0) {
            er.status = TimingReport.EndpointStatus.CDC;
        } else {
            er.status = TimingReport.EndpointStatus.UNCONSTRAINED;
        }
        StringBuilder note = new StringBuilder();
        if (falsePaths > 0) {
            note.append("伪路径对数=").append(falsePaths).append(' ');
        }
        if (cdcPairs > 0) {
            note.append("跨时钟域对数=").append(cdcPairs).append(' ');
        }
        if (uncovered > 0) {
            note.append("未覆盖对数=").append(uncovered).append(' ');
        }
        er.note = note.toString().trim();
    }

    private void buildSummary(TimingReport report) {
        TimingReport.Summary s = report.summary;
        s.worstSetupSlack = Double.POSITIVE_INFINITY;
        s.worstHoldSlack = Double.POSITIVE_INFINITY;
        for (TimingReport.EndpointResult er : report.endpoints) {
            s.totalEndpoints++;
            boolean holdViolated = false;
            switch (er.status) {
                case SETUP_VIOLATED -> {
                    s.setupViolations++;
                }
                case HOLD_VIOLATED -> {
                    s.holdViolations++;
                    holdViolated = true;
                }
                case SETUP_AND_HOLD_VIOLATED -> {
                    s.setupViolations++;
                    s.holdViolations++;
                    holdViolated = true;
                }
                case MET -> s.met++;
                case UNCONSTRAINED -> s.unconstrained++;
                case CDC -> s.cdc++;
            }
            if (holdViolated) {
                report.holdViolations.add(er);
            }
            for (TimingReport.PathPair p : er.pairs) {
                if (p.category == TimingReport.PairCategory.FALSE_PATH) {
                    s.falsePathPairs++;
                }
                if (p.category == TimingReport.PairCategory.CDC) {
                    s.cdcPairs++;
                }
                if (p.category == TimingReport.PairCategory.UNCONSTRAINED) {
                    s.uncoveredPairs++;
                }
            }
            if (er.worstSetupSlack != null && er.worstSetupSlack < s.worstSetupSlack) {
                s.worstSetupSlack = er.worstSetupSlack;
                s.worstSetupEndpoint = er.endpoint;
            }
            if (er.worstHoldSlack != null && er.worstHoldSlack < s.worstHoldSlack) {
                s.worstHoldSlack = er.worstHoldSlack;
                s.worstHoldEndpoint = er.endpoint;
            }
        }
        report.holdViolations.sort(Comparator
                .comparingDouble((TimingReport.EndpointResult er) -> er.worstHoldSlack)
                .thenComparing(er -> er.endpoint));
        if (s.worstSetupEndpoint == null) {
            s.worstSetupSlack = 0;
            s.worstHoldSlack = 0;
        }
    }

    // ---------------------------------------------------------------
    // 节点与工具
    // ---------------------------------------------------------------

    static String portNode(String port) {
        return "port:" + port;
    }

    static String pinNode(String inst, String pin) {
        return "pin:" + inst + "/" + pin;
    }

    static String pinNode(String instPin) {
        return "pin:" + instPin;
    }

    private static String display(String node) {
        return node.startsWith("port:") ? node.substring(5) : node.substring(4);
    }

    private static String[] splitPin(String instPin) {
        int slash = instPin.indexOf('/');
        return new String[]{instPin.substring(0, slash), instPin.substring(slash + 1)};
    }

    private static String[] splitPinNode(String node) {
        return splitPin(node.substring(4));
    }

    private static <T> List<T> sortedCopy(Set<T> set) {
        List<T> list = new ArrayList<>(set);
        Collections.sort(list, Comparator.comparing(String::valueOf));
        return list;
    }

    private static double round(double v) {
        return Math.rint(v * 1e9) / 1e9;
    }
}
