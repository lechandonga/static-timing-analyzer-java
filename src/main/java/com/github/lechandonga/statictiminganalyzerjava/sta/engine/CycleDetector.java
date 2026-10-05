package com.github.lechandonga.statictiminganalyzerjava.sta.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Iterative Tarjan strongly-connected-component search.
 *
 * <p>Implemented without recursion so that a large combinational loop can never cause a
 * {@code StackOverflowError}. Runs on the combinational subgraph only: clock-to-Q arcs are
 * sequential boundaries and never participate in a combinational loop.
 */
final class CycleDetector {

    private CycleDetector() {
    }

    static List<List<String>> findLoops(Map<String, List<TimingGraph.Edge>> edges,
                                        List<String> nodeOrder) {
        int n = nodeOrder.size();
        java.util.Map<String, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(nodeOrder.get(i), i);
        }
        int[] disc = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        java.util.Arrays.fill(disc, -1);

        Deque<Integer> tarjanStack = new ArrayDeque<>();
        List<List<String>> loops = new ArrayList<>();
        int counter = 0;

        for (int start = 0; start < n; start++) {
            if (disc[start] != -1) {
                continue;
            }
            Deque<int[]> work = new ArrayDeque<>();
            work.push(new int[]{start, 0});
            while (!work.isEmpty()) {
                int[] frame = work.peek();
                int node = frame[0];
                if (frame[1] == 0) {
                    disc[node] = counter;
                    low[node] = counter++;
                    tarjanStack.push(node);
                    onStack[node] = true;
                }
                List<TimingGraph.Edge> outgoing = edges.get(nodeOrder.get(node));
                if (outgoing != null && frame[1] < outgoing.size()) {
                    TimingGraph.Edge edge = outgoing.get(frame[1]++);
                    if (edge.clockToQ) {
                        continue;
                    }
                    Integer next = index.get(edge.to);
                    if (next == null) {
                        continue;
                    }
                    if (disc[next] == -1) {
                        work.push(new int[]{next, 0});
                    } else if (onStack[next]) {
                        low[node] = Math.min(low[node], disc[next]);
                    }
                } else {
                    work.pop();
                    if (!work.isEmpty()) {
                        int parent = work.peek()[0];
                        low[parent] = Math.min(low[parent], low[node]);
                    }
                    if (low[node] == disc[node]) {
                        List<String> component = new ArrayList<>();
                        int popped;
                        do {
                            popped = tarjanStack.pop();
                            onStack[popped] = false;
                            component.add(nodeOrder.get(popped));
                        } while (popped != node);
                        boolean selfLoop = outgoing != null && outgoing.stream()
                                .anyMatch(edge -> edge.to.equals(nodeOrder.get(node))
                                        && !edge.clockToQ);
                        if (component.size() > 1 || selfLoop) {
                            component.sort(String::compareTo);
                            loops.add(component);
                        }
                    }
                }
            }
        }
        loops.sort((a, b) -> String.join(">", a).compareTo(String.join(">", b)));
        return loops;
    }
}
