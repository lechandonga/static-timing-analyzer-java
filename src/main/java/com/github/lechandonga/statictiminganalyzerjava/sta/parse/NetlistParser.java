package com.github.lechandonga.statictiminganalyzerjava.sta.parse;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.CellLibrary;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Netlist;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 网表格式：
 * <pre>
 * input  N1
 * output N9
 * inst U1 BUF2
 *   pin A N1
 *   pin Y N2
 * endinst
 * </pre>
 * input 声明的线网没有驱动；output 声明的线网由内部引脚驱动，未挂任何 reader 也合法。
 */
public class NetlistParser {

    public Netlist parse(Path file, CellLibrary lib) {
        String src = file.toString();
        List<LineReader.RawLine> lines = LineReader.read(file);
        Netlist netlist = new Netlist();
        Netlist.Instance current = null;

        for (LineReader.RawLine rl : lines) {
            String[] t = LineReader.tokens(rl, src, 1, 3);
            String head = t[0].toLowerCase();
            switch (head) {
                case "input" -> {
                    if (t.length != 2) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "input 用法: input <线网名>");
                    }
                    addPrimary(netlist.primaryInputs(), t[1], src, rl.number(), "input");
                    netlist.nets().computeIfAbsent(t[1], Netlist.Net::new);
                }
                case "output" -> {
                    if (t.length != 2) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "output 用法: output <线网名>");
                    }
                    addPrimary(netlist.primaryOutputs(), t[1], src, rl.number(), "output");
                    netlist.nets().computeIfAbsent(t[1], Netlist.Net::new);
                }
                case "inst" -> {
                    if (t.length != 3) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "inst 用法: inst <实例名> <单元类型>");
                    }
                    if (current != null) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "前一个 inst 缺少 endinst");
                    }
                    if (netlist.instances().containsKey(t[1])) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(), "实例重复定义: " + t[1]);
                    }
                    if (!lib.cells().containsKey(t[2])) {
                        throw new StaException("CELL_NOT_FOUND", src, rl.number(),
                                "实例 " + t[1] + " 引用的单元类型不在库中: " + t[2]);
                    }
                    current = new Netlist.Instance(t[1], t[2]);
                    netlist.instances().put(t[1], current);
                }
                case "endinst" -> {
                    if (current == null) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "endinst 没有匹配的 inst");
                    }
                    current = null;
                }
                case "pin" -> {
                    if (current == null) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "pin 行必须位于 inst/endinst 内");
                    }
                    if (t.length != 3) {
                        throw new StaException("SYNTAX_ERROR", src, rl.number(), "pin 用法: pin <引脚名> <线网名>");
                    }
                    CellLibrary.CellType ct = lib.cells().get(current.cellType);
                    if (!ct.pins.containsKey(t[1])) {
                        throw new StaException("PIN_NOT_FOUND", src, rl.number(),
                                "单元类型 " + ct.name + " 不存在引脚 " + t[1] + "（实例 " + current.name + "）");
                    }
                    if (current.pinNet.containsKey(t[1])) {
                        throw new StaException("DUPLICATE_DEFINITION", src, rl.number(),
                                "实例 " + current.name + " 引脚重复连接: " + t[1]);
                    }
                    current.pinNet.put(t[1], t[2]);
                    netlist.nets().computeIfAbsent(t[2], Netlist.Net::new);
                }
                default -> throw new StaException("SYNTAX_ERROR", src, rl.number(), "未知关键字: " + head);
            }
        }
        if (current != null) {
            throw new StaException("SYNTAX_ERROR", src, 0, "文件结束时仍有未闭合的 inst");
        }
        if (netlist.instances().isEmpty()) {
            throw new StaException("NETLIST_EMPTY", src, 0, "网表中没有任何实例");
        }
        crossValidate(netlist, lib, src);
        return netlist;
    }

    private void addPrimary(java.util.Set<String> set, String net, String src, int line, String kind) {
        if (!set.add(net)) {
            throw new StaException("DUPLICATE_DEFINITION", src, line, kind + " 线网重复声明: " + net);
        }
    }

    /** 构建线网驱动/负载关系并做结构性校验。 */
    private void crossValidate(Netlist netlist, CellLibrary lib, String src) {
        for (Netlist.Instance inst : netlist.instances().values()) {
            CellLibrary.CellType ct = lib.cells().get(inst.cellType);
            for (CellLibrary.Pin pin : ct.pins.values()) {
                String netName = inst.pinNet.get(pin.name());
                if (netName == null) {
                    throw new StaException("PIN_UNCONNECTED", src, 0,
                            "实例 " + inst.name + " 的引脚 " + pin.name() + " 未连接任何线网");
                }
                Netlist.Net net = netlist.nets().get(netName);
                String ref = inst.name + "/" + pin.name();
                switch (pin.role()) {
                    case COMB, SEQ_IN, CLK -> {
                        if (!net.readers.add(ref)) {
                            throw new StaException("NETLIST_STRUCTURE_ERROR", src, 0, "线网负载重复: " + ref);
                        }
                    }
                    case SEQ_OUT -> attachDriver(net, ref, netlist, src);
                }
            }
            // 组合单元：延时弧输出脚为驱动，其余引脚为负载。
            if (!ct.sequential) {
                java.util.Set<String> drivenPins = new java.util.HashSet<>();
                for (String arc : ct.delays.keySet()) {
                    String outPin = arc.split("->")[1];
                    if (drivenPins.add(outPin)) {
                        String netName = inst.pinNet.get(outPin);
                        Netlist.Net net = netlist.nets().get(netName);
                        net.readers.remove(inst.name + "/" + outPin);
                        attachDriver(net, inst.name + "/" + outPin, netlist, src);
                    }
                }
            }
        }
        for (Map.Entry<String, Netlist.Net> e : netlist.nets().entrySet()) {
            Netlist.Net net = e.getValue();
            boolean isInput = netlist.primaryInputs().contains(net.name);
            if (net.driver == null && !isInput) {
                throw new StaException("NET_UNDRIVEN", src, 0,
                        "线网 " + net.name + " 没有驱动，且未声明为 input");
            }
            if (isInput && net.driver != null) {
                throw new StaException("NET_DRIVER_CONFLICT", src, 0,
                        "主输入线网 " + net.name + " 不允许再有内部驱动 " + net.driver);
            }
        }
    }

    private void attachDriver(Netlist.Net net, String ref, Netlist netlist, String src) {
        if (net.driver != null) {
            throw new StaException("NET_MULTI_DRIVER", src, 0,
                    "线网 " + net.name + " 存在多个驱动: " + net.driver + ", " + ref);
        }
        net.driver = ref;
    }
}
