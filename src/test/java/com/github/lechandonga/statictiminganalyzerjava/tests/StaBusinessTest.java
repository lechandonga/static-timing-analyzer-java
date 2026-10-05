package com.github.lechandonga.statictiminganalyzerjava.tests;

import com.github.lechandonga.statictiminganalyzerjava.sta.StaException;
import com.github.lechandonga.statictiminganalyzerjava.sta.analysis.TimingReport;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.ReportRenderer;
import com.github.lechandonga.statictiminganalyzerjava.sta.service.StaService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态时序分析业务测试。
 * 用例分组（日志可见）：B1 正常建立/保持；B2 组合环；B3 未约束端点；
 * B4 跨时钟域；B5 例外冲突；B6 非法输入；B7 重复分析与声明顺序一致性。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StaBusinessTest {

    private static final Logger log = LoggerFactory.getLogger("STA-BUSINESS");

    private static final Path CASES = Paths.get("src", "test", "resources", "cases");
    private static final Path COMMON_LIB = CASES.resolve("cells.lib");

    private TimingReport runCase(String name) {
        return new StaService().analyze(
                COMMON_LIB,
                CASES.resolve(name).resolve("design.net"),
                CASES.resolve(name).resolve("constraints.sdc"));
    }

    private Map<String, TimingReport.EndpointResult> byName(TimingReport r) {
        java.util.Map<String, TimingReport.EndpointResult> m = new java.util.TreeMap<>();
        for (TimingReport.EndpointResult ep : r.endpoints) {
            m.put(ep.endpoint, ep);
        }
        return m;
    }

    @Test
    @Order(1)
    @DisplayName("B1-1 正常电路：setup/hold slack、最差路径定位、汇总与逐端点一致")
    void normalSetupHoldAndSummary() {
        log.info("[B1 正常建立/保持] 用例 normal：两级寄存器 + 输出端口");
        TimingReport r = runCase("normal");
        log.info("\n{}", ReportRenderer.render(r));

        Map<String, TimingReport.EndpointResult> eps = byName(r);
        TimingReport.EndpointResult dff = eps.get("pin:U3/D");
        TimingReport.EndpointResult out = eps.get("port:OUT1");
        assertNotNull(dff);
        assertNotNull(out);

        // U1/Q -> U3/D: arrival = clk_to_q(0.08) + BUF(0.30) = 0.38
        // setup req = 1.0 - 0.05 = 0.95 ; setup slack = 0.57
        // hold req = 0.02 ; hold slack = 0.40 - 0.02 = 0.38
        assertEquals(0.38, dff.pairs.get(0).arrival, 1e-9);
        assertEquals(0.57, dff.worstSetupSlack, 1e-9);
        assertEquals(0.40, dff.worstHoldSlack, 1e-9);
        assertEquals(TimingReport.EndpointStatus.MET, dff.status);
        assertTrue(dff.worstSetupPath.contains("U2/Y"),
                "最差路径必须能定位到具体单元引脚");

        // OUT1: IN1 input_delay 0.2 -> U1/D, 该路径不到 OUT1；OUT1 由 U4 驱动：
        // arrival = 0.08 + 0.05 = 0.13（U3/Q -> INV），setup req = 1.0 - 0.10
        assertEquals(TimingReport.EndpointStatus.MET, out.status);
        assertEquals(0.77, out.worstSetupSlack, 1e-9);

        // 汇总与逐端点统计对得上
        long setupViolations = r.endpoints.stream()
                .filter(e -> e.status == TimingReport.EndpointStatus.SETUP_VIOLATED).count();
        long holdViolations = r.endpoints.stream()
                .filter(e -> e.status == TimingReport.EndpointStatus.HOLD_VIOLATED).count();
        assertEquals(setupViolations, r.summary.setupViolations);
        assertEquals(holdViolations, r.summary.holdViolations);
        assertEquals(3, r.summary.met);
        assertEquals(0, r.summary.unconstrained);
        assertEquals(0, r.summary.cdc);
        log.info("[B1 正常建立/保持] 结果：全部通过，汇总与端点一致 ✓");
    }

    @Test
    @Order(2)
    @DisplayName("B2-1 组合环：返回可定位的环节点，且分析不死循环")
    void combinationalCycleIsReported() {
        log.info("[B2 组合环] 用例 cycle：U2/U3 两个反相器首尾相连");
        TimingReport r = runCase("cycle");
        log.info("\n{}", ReportRenderer.render(r));

        assertEquals(TimingReport.Status.COMBINATIONAL_CYCLE, r.status);
        assertEquals(1, r.cycles.size());
        assertTrue(r.cycles.get(0).nodes.contains("pin:U3/B"));
        assertTrue(r.cycles.get(0).nodes.contains("pin:U4/Y"));
        assertTrue(r.endpoints.isEmpty(), "存在组合环时不得给出看似正常的时序结论");
        log.info("[B2 组合环] 结果：定位到环 {} ✓", r.cycles.get(0).description);
    }

    @Test
    @Order(3)
    @DisplayName("B3-1 未约束端点：单列归类，不计通过也不计违例")
    void unconstrainedEndpointsClassifiedSeparately() {
        log.info("[B3 未约束端点] 用例 unconstrained：FREE 无输入延时，OUT1 无输出延时");
        TimingReport r = runCase("unconstrained");
        log.info("\n{}", ReportRenderer.render(r));

        Map<String, TimingReport.EndpointResult> eps = byName(r);
        assertEquals(TimingReport.EndpointStatus.UNCONSTRAINED,
                eps.get("pin:U1/D").status);
        // OUT1 只有来自 U1/Q 的路径，而 OUT1 无输出延时 => 同为未约束
        assertEquals(TimingReport.EndpointStatus.UNCONSTRAINED,
                eps.get("port:OUT1").status);
        assertEquals(2, r.summary.uncoveredPairs, "未覆盖路径对单独计数");
        assertEquals(2, r.summary.unconstrained, "未约束端点既不算通过也不算违例");
        assertEquals(0, r.summary.setupViolations + r.summary.holdViolations);
        log.info("[B3 未约束端点] 结果：未约束端点数={}, 未覆盖对数={} ✓",
                r.summary.unconstrained, r.summary.uncoveredPairs);
    }

    @Test
    @Order(4)
    @DisplayName("B4-1 跨时钟域：默认归 CDC 不按单周期算；多周期例外按捕获时钟放宽")
    void crossClockDomainClassification() {
        log.info("[B4 跨时钟域] 用例 cdc：CLK1 -> U3(CLK2)");
        TimingReport r = runCase("cdc");
        log.info("\n{}", ReportRenderer.render(r));

        TimingReport.EndpointResult ep = byName(r).get("pin:U3/D");
        assertEquals(TimingReport.EndpointStatus.CDC, ep.status);
        TimingReport.PathPair pair = ep.pairs.get(0);
        assertEquals(TimingReport.PairCategory.CDC, pair.category);
        assertEquals("CLK1", pair.launchClock);
        assertEquals("CLK2", pair.captureClock);
        assertEquals(1, r.summary.cdc);
        assertEquals(0, r.summary.setupViolations + r.summary.holdViolations,
                "跨时钟域不得按同一时钟周期产生违例统计");
        log.info("[B4 跨时钟域] 结果：路径归为 CDC，未混入违例 ✓");
    }

    @Test
    @Order(5)
    @DisplayName("B5-1 例外冲突：伪路径与多周期同粒度冲突，错误码 EXCEPTION_CONFLICT")
    void exceptionConflictRejected() {
        log.info("[B5 例外冲突] 用例 conflict：同一对端点点同时声明 false_path/multicycle");
        StaException ex = assertThrows(StaException.class, () -> runCase("conflict"));
        log.info("[B5 例外冲突] 捕获预期异常 code={} message={}", ex.getCode(), ex.getMessage());
        assertEquals("EXCEPTION_CONFLICT", ex.getCode());
        log.info("[B5 例外冲突] 结果：确定性拒绝 ✓");
    }

    @Test
    @Order(6)
    @DisplayName("B6-1/B6-2/B6-3 非法输入：单位非法/引用缺失/时钟源缺失给出不同错误码")
    void illegalInputsHaveDistinguishableCodes() {
        StaService service = new StaService();
        log.info("[B6 非法输入] B6-1 非法单位");
        StaException e1 = assertThrows(StaException.class, () -> service.analyze(
                CASES.resolve("illegal_lib").resolve("cells.lib"),
                CASES.resolve("illegal_lib").resolve("design.net"),
                CASES.resolve("illegal_lib").resolve("constraints.sdc")));
        log.info("[B6 非法输入]   code={} line={} {}", e1.getCode(), e1.getLine(), e1.getMessage());
        assertEquals("UNIT_UNSUPPORTED", e1.getCode());

        log.info("[B6 非法输入] B6-2 网表引用库中不存在的单元");
        StaException e2 = assertThrows(StaException.class, () -> runCase("illegal_net"));
        log.info("[B6 非法输入]   code={} line={} {}", e2.getCode(), e2.getLine(), e2.getMessage());
        assertEquals("CELL_NOT_FOUND", e2.getCode());

        log.info("[B6 非法输入] B6-3 约束时钟源在网表中不存在");
        StaException e3 = assertThrows(StaException.class, () -> runCase("illegal_sdc"));
        log.info("[B6 非法输入]   code={} line={} {}", e3.getCode(), e3.getLine(), e3.getMessage());
        assertEquals("CLOCK_SOURCE_NOT_FOUND", e3.getCode());
        log.info("[B6 非法输入] 结果：三类失败原因可区分 ✓");
    }

    @Test
    @Order(7)
    @DisplayName("B7-1/B7-2 可复现：重复分析结果一致；约束声明顺序调整不影响结论")
    void deterministicAcrossRunsAndOrderings() throws IOException {
        log.info("[B7 一致性] B7-1 同一份输入连续分析两次，文本报告逐字节一致");
        StaService service = new StaService();
        String t1 = ReportRenderer.render(runCase("normal"));
        String t2 = ReportRenderer.render(runCase("normal"));
        assertEquals(t1, t2);

        log.info("[B7 一致性] B7-2 约束声明顺序打乱（order 用例）结论不变");
        TimingReport reordered = service.analyze(
                COMMON_LIB,
                CASES.resolve("order").resolve("design.net"),
                CASES.resolve("order").resolve("constraints.sdc"));
        Path baseline = Files.createTempFile("sta-baseline", ".txt");
        Path other = Files.createTempFile("sta-reordered", ".txt");
        Files.writeString(baseline, t1);
        // order 用例与 normal 网表不同，这里用“同约束重排”的等价性：
        // 重新把 order 的约束按正序排列后再分析，两次结论必须一致
        Path reorderedSdc = CASES.resolve("order").resolve("constraints.sdc");
        String sdcText = Files.readString(reorderedSdc);
        Path canonicalSdc = Files.createTempFile("sta-order", ".sdc");
        Files.writeString(canonicalSdc,
                "create_clock CLK -period 1.0\n"
                        + "set_input_delay 0.20 -clock CLK [get_ports IN1]\n"
                        + "set_output_delay 0.10 -clock CLK [get_ports OUT1]\n");
        TimingReport canonical = service.analyze(
                COMMON_LIB, CASES.resolve("order").resolve("design.net"), canonicalSdc);
        Files.writeString(other, ReportRenderer.render(reordered));
        assertEquals(ReportRenderer.render(canonical), ReportRenderer.render(reordered));
        log.info("重排顺序报告:\n{}", Files.readString(other));
        assertTrue(sdcText.lines().findFirst().orElseThrow().startsWith("set_output_delay"),
                "确认 order 用例确实是乱序声明");
        log.info("[B7 一致性] 结果：重复分析与声明顺序调整后结论完全一致 ✓");
    }
}
