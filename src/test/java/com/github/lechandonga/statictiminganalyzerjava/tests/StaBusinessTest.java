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
        // hold req = 0.02 ; hold slack = 0.38 - 0.02 = 0.36
        // （保持检查口径修正：hold required = N_hold*T + h，旧版本误为 -h，
        //   旧期望值 0.40 = 0.38 + 0.02 系按错误口径算出，此处按正确口径更新）
        assertEquals(0.38, dff.pairs.get(0).arrival, 1e-9);
        assertEquals(0.57, dff.worstSetupSlack, 1e-9);
        assertEquals(0.36, dff.worstHoldSlack, 1e-9);
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

    private TimingReport runCaseWithLib(String name) {
        Path dir = CASES.resolve(name);
        Path lib = Files.isRegularFile(dir.resolve("cells.lib")) ? dir.resolve("cells.lib") : COMMON_LIB;
        return new StaService().analyze(lib, dir.resolve("design.net"), dir.resolve("constraints.sdc"));
    }

    @Test
    @Order(8)
    @DisplayName("B8-1 时钟不确定度：setup 扣减、hold 增加，路径级>时钟级>全局")
    void uncertaintyAppliesToBothChecks() {
        log.info("[B8 时钟不确定度] 用例 uncertainty：全局/时钟/路径三级 + 同层取最大");
        TimingReport r = runCase("uncertainty");
        log.info("\n{}", ReportRenderer.render(r));

        Map<String, TimingReport.EndpointResult> eps = byName(r);
        // U1/Q -> U3/D：路径级命中两条（0.20 与 0.15），同层取最大 0.20，覆盖时钟级 0.10 与全局 0.05
        TimingReport.PathPair p = eps.get("pin:U3/D").worstSetup;
        assertEquals(0.20, p.setupUncertainty, 1e-9);
        assertEquals(0.38, p.arrival, 1e-9);
        assertEquals(0.75, p.setupRequired, 1e-9, "setup 要求 = 1.0 - 0.05 - 0.20");
        assertEquals(0.37, p.setupSlack, 1e-9);
        // hold 只有全局 0.03：hold 要求 = 0.02 + 0.03
        TimingReport.PathPair h = eps.get("pin:U3/D").worstHold;
        assertEquals(0.03, h.holdUncertainty, 1e-9);
        assertEquals(0.05, h.holdRequired, 1e-9);
        assertEquals(0.33, h.holdSlack, 1e-9);
        // 输出端口路径：时钟级 0.10 覆盖全局 0.05
        TimingReport.PathPair out = eps.get("port:OUT1").worstSetup;
        assertEquals(0.10, out.setupUncertainty, 1e-9);
        assertEquals(0.80, out.setupRequired, 1e-9, "setup 要求 = 1.0 - 0.10(输出延时) - 0.10(不确定度)");
        assertEquals(0.67, out.setupSlack, 1e-9);
        log.info("[B8 时钟不确定度] 结果：setup/hold 两个方向与三级优先级均正确 ✓");
    }

    @Test
    @Order(9)
    @DisplayName("B8-2 不确定度：声明顺序调整不影响结论")
    void uncertaintyOrderIndependent() {
        log.info("[B8 时钟不确定度] 用例 uncertainty_order：声明完全乱序");
        String a = ReportRenderer.render(runCase("uncertainty"));
        String b = ReportRenderer.render(runCase("uncertainty_order"));
        assertEquals(a, b, "不确定度结论不得随声明顺序变化");
        log.info("[B8 时钟不确定度] 结果：乱序后报告逐字节一致 ✓");
    }

    @Test
    @Order(10)
    @DisplayName("B8-3/B8-4 不确定度：重复声明与非法数值以专用错误码失败")
    void uncertaintyIllegalRejected() {
        StaException dup = assertThrows(StaException.class, () -> runCase("uncertainty_dup"));
        log.info("[B8 时钟不确定度] 重复声明 code={}", dup.getCode());
        assertEquals("UNCERTAINTY_DUPLICATE", dup.getCode());
        StaException bad = assertThrows(StaException.class, () -> runCase("uncertainty_bad"));
        log.info("[B8 时钟不确定度] 非法数值 code={}", bad.getCode());
        assertEquals("UNCERTAINTY_INVALID", bad.getCode());
        log.info("[B8 时钟不确定度] 结果：失败原因可区分、不静默兜底 ✓");
    }

    @Test
    @Order(11)
    @DisplayName("B8-5 不确定度：作用于多周期例外放开的跨时钟域路径")
    void uncertaintyAppliesToMulticycleCdc() {
        log.info("[B8 时钟不确定度] 用例 uncertainty_cdc：CLK1->U3(CLK2) 多周期 x2");
        TimingReport r = runCase("uncertainty_cdc");
        log.info("\n{}", ReportRenderer.render(r));
        TimingReport.PathPair p = byName(r).get("pin:U3/D").worstSetup;
        assertEquals(TimingReport.PairCategory.CONSTRAINED, p.category);
        assertEquals(0.10, p.setupUncertainty, 1e-9);
        assertEquals(1.45, p.setupRequired, 1e-9, "setup 要求 = 2*0.8 - 0.05 - 0.10");
        assertEquals(1.07, p.setupSlack, 1e-9);
        TimingReport.PathPair h = byName(r).get("pin:U3/D").worstHold;
        assertEquals(0.05, h.holdUncertainty, 1e-9);
        assertEquals(-0.73, h.holdRequired, 1e-9, "hold 要求 = -1*0.8 + 0.02 + 0.05");
        assertEquals(1.11, h.holdSlack, 1e-9);
        log.info("[B8 时钟不确定度] 结果：多周期 CDC 路径两个方向都生效 ✓");
    }

    @Test
    @Order(12)
    @DisplayName("B9-1 时序降额：早/晚角分离，实例>单元类型>全局，clk_to_q 同步降额")
    void derateCornersAndOverride() {
        log.info("[B9 时序降额] 用例 derate：全局 1.1/0.9，BUF 晚 1.2，U2 早 0.8");
        TimingReport r = runCase("derate");
        log.info("\n{}", ReportRenderer.render(r));

        TimingReport.EndpointResult ep = byName(r).get("pin:U3/D");
        // late：clk_to_q 0.08*1.1 + BUF 0.30*1.2 = 0.448；setup 裕量 = 0.95 - 0.448
        assertEquals(0.448, ep.worstSetup.arrival, 1e-9);
        assertEquals(0.502, ep.worstSetupSlack, 1e-9);
        // early：clk_to_q 0.08*0.9 + BUF 0.30*0.8(实例覆盖) = 0.312；hold 裕量 = 0.312 - 0.02
        assertEquals(0.312, ep.worstHold.earlyArrival, 1e-9);
        assertEquals(0.292, ep.worstHoldSlack, 1e-9);
        // 报告中能看出最终生效的系数与覆盖层级
        assertTrue(ep.worstSetup.derateLate.contains("U2[BUF]*1.2(cell_type)"),
                "晚角应命中单元类型级: " + ep.worstSetup.derateLate);
        assertTrue(ep.worstSetup.derateLate.contains("U1[DFF]*1.1(global)"),
                "clk_to_q 应按晚角全局降额: " + ep.worstSetup.derateLate);
        assertTrue(ep.worstHold.derateEarly.contains("U2[BUF]*0.8(instance)"),
                "早角应命中实例级: " + ep.worstHold.derateEarly);
        log.info("[B9 时序降额] 结果：早晚角分离、三级覆盖、clk_to_q 跟随对应侧 ✓");
    }

    @Test
    @Order(13)
    @DisplayName("B9-2/B9-3/B9-4 降额：重复声明/非法系数/引用缺失以专用错误码失败")
    void derateIllegalRejected() {
        StaException dup = assertThrows(StaException.class, () -> runCase("derate_dup"));
        log.info("[B9 时序降额] 重复声明 code={}", dup.getCode());
        assertEquals("DERATE_DUPLICATE", dup.getCode());
        StaException bad = assertThrows(StaException.class, () -> runCase("derate_bad"));
        log.info("[B9 时序降额] 非法系数 code={}", bad.getCode());
        assertEquals("DERATE_INVALID", bad.getCode());
        StaException ref = assertThrows(StaException.class, () -> runCase("derate_ref"));
        log.info("[B9 时序降额] 引用缺失 code={}", ref.getCode());
        assertEquals("DERATE_REF_NOT_FOUND", ref.getCode());
        log.info("[B9 时序降额] 结果：三类失败原因可区分 ✓");
    }

    @Test
    @Order(14)
    @DisplayName("B10-1 保持违例探针：hold=0.5 寄存器与输出延时>到达的输出路径报出负 slack")
    void holdViolationProbes() {
        log.info("[B10 保持违例探针] 用例 hold_probe：hold=0.5ns，Q 直达 D，输出延时 0.5");
        TimingReport r = runCaseWithLib("hold_probe");
        log.info("\n{}", ReportRenderer.render(r));

        Map<String, TimingReport.EndpointResult> eps = byName(r);
        // 探针 1：U1/Q 直达 U2/D，到达 0.08 < hold 0.5
        TimingReport.EndpointResult reg = eps.get("pin:U2/D");
        assertEquals(TimingReport.EndpointStatus.HOLD_VIOLATED, reg.status);
        assertEquals(-0.42, reg.worstHoldSlack, 1e-9);
        assertEquals("U1/Q", reg.worstHold.startpoint, "违例需定位到具体起点");
        assertTrue(reg.worstHoldPath.contains("U2/D"), "违例路径需定位到端点引脚");
        // 探针 2：输出延时 0.5 > 数据到达 0.08
        TimingReport.EndpointResult out = eps.get("port:OUT1");
        assertEquals(TimingReport.EndpointStatus.HOLD_VIOLATED, out.status);
        assertEquals(-0.42, out.worstHoldSlack, 1e-9);
        // 到达足够大的路径不得误报
        assertEquals(TimingReport.EndpointStatus.MET, eps.get("pin:U4/D").status);
        assertEquals(TimingReport.EndpointStatus.MET, eps.get("pin:U1/D").status);
        // 所有保持违例端点单独列出，且与逐端点统计对得上
        assertEquals(java.util.List.of("pin:U2/D", "port:OUT1"), r.summary.holdViolationEndpoints);
        assertEquals(r.summary.holdViolationEndpoints.size(), r.summary.holdViolations);
        assertEquals(3, r.summary.met);
        log.info("[B10 保持违例探针] 结果：两处违例均报出并定位，无误报 ✓");
    }

    @Test
    @Order(15)
    @DisplayName("B11-1 深组合链回归：2000 级单元链不爆栈、结论正确")
    void deepCombinationalChainRegression() throws IOException {
        log.info("[B11 深组合链] 生成 2000 级 BUF 链网表");
        int depth = 2000;
        Path dir = Files.createTempDirectory("sta-deep");
        Files.writeString(dir.resolve("cells.lib"),
                "unit ns\n"
                        + "cell BUF comb\n  pin A comb\n  pin Y comb\n  delay A Y 0.01\nendcell\n"
                        + "cell DFF seq\n  pin D seq_in\n  pin CK clk\n  pin Q seq_out\n"
                        + "  timing setup=0.05 hold=0.02 clk_to_q=0.08\nendcell\n");
        StringBuilder net = new StringBuilder("input CLK\ninput IN1\noutput OUT1\n"
                + "inst U0 DFF\n  pin D IN1\n  pin CK CLK\n  pin Q n0\nendinst\n");
        for (int i = 1; i <= depth; i++) {
            net.append("inst B").append(i).append(" BUF\n  pin A n").append(i - 1)
                    .append("\n  pin Y n").append(i).append("\nendinst\n");
        }
        net.append("inst U1 DFF\n  pin D n").append(depth).append("\n  pin CK CLK\n  pin Q OUT1\nendinst\n");
        Files.writeString(dir.resolve("design.net"), net.toString());
        Files.writeString(dir.resolve("constraints.sdc"),
                "create_clock CLK -period 100.0\n"
                        + "set_clock_uncertainty 0.01 -setup\n"
                        + "set_timing_derate 1.05 -late\n"
                        + "set_input_delay 0.1 -clock CLK [get_ports IN1]\n"
                        + "set_output_delay 0.1 -clock CLK [get_ports OUT1]\n");

        long t0 = System.nanoTime();
        TimingReport r = new StaService().analyze(dir.resolve("cells.lib"),
                dir.resolve("design.net"), dir.resolve("constraints.sdc"));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("[B11 深组合链] 分析耗时 {}ms", ms);

        TimingReport.EndpointResult ep = byName(r).get("pin:U1/D");
        // late 到达 = 0.08*1.05 + 2000*0.01*1.05 = 21.084
        assertEquals(21.084, ep.worstSetup.arrival, 1e-6);
        assertEquals(0.01, ep.worstSetup.setupUncertainty, 1e-9);
        assertEquals(TimingReport.EndpointStatus.MET, ep.status);
        assertTrue(ms < 30_000, "深链分析耗时不应失控");
        log.info("[B11 深组合链] 结果：{} 级链在 {}ms 内完成，叠加不确定度/降额结论正确 ✓", depth, ms);
    }
}
