package com.github.lechandonga.statictiminganalyzerjava.sta.parser;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Instance;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Net;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.Netlist;
import com.github.lechandonga.statictiminganalyzerjava.sta.model.Models.PinRef;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the local netlist format:
 * <pre>
 * input A B
 * output Y
 * instance u1 INV
 * net n1 driver A load u1/A
 * net n2 driver u1/Z load Y
 * </pre>
 * Every pin reference is {@code <instance>/<pin>}; top-level ports use the bare name.
 */
public final class NetlistParser {

    private NetlistParser() {
    }

    public static Netlist parse(String fileName, String content) {
        List<LineReader.Line> lines = LineReader.read(fileName, content);
        Netlist netlist = new Netlist();
        Set<String> portNames = new LinkedHashSet<>();

        for (LineReader.Line line : lines) {
            String[] tokens = line.text().split("\\s+");
            switch (tokens[0]) {
                case "input" -> addPorts(fileName, line, tokens, netlist.getInputPorts(),
                        portNames);
                case "output" -> addPorts(fileName, line, tokens, netlist.getOutputPorts(),
                        portNames);
                case "instance" -> {
                    if (tokens.length != 3) {
                        throw LineReader.fail(fileName, line,
                                "expected 'instance <name> <cell>'");
                    }
                    if (netlist.getInstances().containsKey(tokens[1])) {
                        throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                                fileName + ":" + line.number()
                                        + ": instance '" + tokens[1] + "' is defined twice");
                    }
                    netlist.getInstances().put(tokens[1], new Instance(tokens[1], tokens[2]));
                }
                case "net" -> parseNet(fileName, line, tokens, netlist);
                default -> throw LineReader.fail(fileName, line,
                        "unknown netlist statement '" + tokens[0]
                                + "' (expected input/output/instance/net)");
            }
        }
        if (netlist.getNets().isEmpty()) {
            throw new StaException(StaException.Code.FORMAT_ERROR,
                    fileName + ": netlist defines no nets");
        }
        return netlist;
    }

    private static void addPorts(String fileName, LineReader.Line line, String[] tokens,
                                 List<String> target, Set<String> allPorts) {
        if (tokens.length < 2) {
            throw LineReader.fail(fileName, line,
                    "'" + tokens[0] + "' needs at least one port name");
        }
        for (int i = 1; i < tokens.length; i++) {
            String port = tokens[i];
            if (port.indexOf('/') >= 0) {
                throw LineReader.fail(fileName, line,
                        "port name '" + port + "' must not contain '/'");
            }
            if (!allPorts.add(port)) {
                throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                        fileName + ":" + line.number()
                                + ": port '" + port + "' is declared more than once");
            }
            target.add(port);
        }
    }

    private static void parseNet(String fileName, LineReader.Line line, String[] tokens,
                                 Netlist netlist) {
        if (tokens.length < 5 || !tokens[2].equals("driver") || !tokens[4].equals("load")) {
            throw LineReader.fail(fileName, line,
                    "expected 'net <name> driver <ref> load <ref> [<ref> ...]'");
        }
        String name = tokens[1];
        if (netlist.getNets().containsKey(name)) {
            throw new StaException(StaException.Code.DUPLICATE_DEFINITION,
                    fileName + ":" + line.number()
                            + ": net '" + name + "' is defined more than once");
        }
        PinRef driver = PinReferences.parse(fileName, line, tokens[3]);
        Net net = new Net(name, driver);
        for (int i = 5; i < tokens.length; i++) {
            net.getLoads().add(PinReferences.parse(fileName, line, tokens[i]));
        }
        netlist.getNets().put(name, net);
    }
}
