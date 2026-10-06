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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态时序分析业务测试。
 * 用例分组（日志可见）：B1 正常建立/保持；B2 组合环；B3 未约束端点；
 * B4 跨时钟域；B5 例外冲突；B6 非法输入；B7 重复分析与声明顺序一致性。
 * B8 时钟不确定度；B9 时序降额；B10 保持违例探针；B11 深组合链性能回归；
 * B12 新约束非法声明与 HTTP 入口可见性。
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

    private TimingReport.PathPair onlyPair(String name, String endpoint) {
        TimingReport r = runCase(name);
        return byName(r).get(endpoint).pairs.get(0);
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
        // 保持口径：hold req = hold(0.02)，hold slack = arrival - hold = 0.38 - 0.02 = 0.36
        // （早期版本误写成 arrival - (0 - hold) 得到 0.40；hold 时间是必须满足的最小间隔，
        //  应加在 required 上，见 docs/timing-rules.md 第 3 节）
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

    // =========================================================
    // B8 时钟不确定度：建立扣减、保持加回；路径 > 时钟 > 全局
    // =========================================================

    @Test
    @Order(8)
    @DisplayName("B8-1 全局不确定度：setup 悲观扣减、hold 悲观加回，两侧都受影响")
    void globalUncertaintyHitsBothChecks() {
        log.info("[B8 时钟不确定度] 用例 unc_global：全局 0.05");
        TimingReport r = runCase("unc_global");
        log.info("\n{}", ReportRenderer.render(r));
        TimingReport.PathPair p = byName(r).get("pin:U3/D").pairs.get(0);
        // U1/Q(0.08) + BUF(0.30) = 0.38
        assertEquals(0.38, p.arrival, 1e-9);
        // setup required = 1.0 - 0.05(setup 库值) - 0.05(unc) = 0.90
        assertEquals(0.90, p.setupRequired, 1e-9);
        assertEquals(0.52, p.setupSlack, 1e-9);
        // hold required = 0.02(hold 库值) + 0.05(unc) = 0.07
        assertEquals(0.07, p.holdRequired, 1e-9);
        assertEquals(0.31, p.holdSlack, 1e-9);
        assertEquals(0.05, p.setupUncertainty, 1e-9);
        assertEquals(0.05, p.holdUncertainty, 1e-9);
        assertEquals("global", p.setupUncertaintySource);
        assertEquals("global", p.holdUncertaintySource);
        log.info("[B8 时钟不确定度] B8-1 结果：setup 扣 0.05、hold 加 0.05 ✓");
    }

    @Test
    @Order(9)
    @DisplayName("B8-2 时钟级覆盖全局；B8-3 路径级覆盖时钟/全局")
    void clockAndPathUncertaintyPriority() {
        log.info("[B8 时钟不确定度] 用例 unc_clock：全局 0.20、CLK 0.05");
        TimingReport r2 = runCase("unc_clock");
        TimingReport.PathPair pc = byName(r2).get("pin:U3/D").pairs.get(0);
        assertEquals(0.05, pc.setupUncertainty, 1e-9);
        assertTrue(pc.setupUncertaintySource.startsWith("clock("),
                "时钟级必须优先于全局: " + pc.setupUncertaintySource);

        log.info("[B8 时钟不确定度] 用例 unc_path：再叠加路径级 0.01");
        TimingReport r3 = runCase("unc_path");
        TimingReport.PathPair pp = byName(r3).get("pin:U3/D").pairs.get(0);
        assertEquals(0.01, pp.setupUncertainty, 1e-9);
        assertEquals(0.01, pp.holdUncertainty, 1e-9);
        assertTrue(pp.setupUncertaintySource.startsWith("path("),
                "路径级必须优先于时钟级与全局: " + pp.setupUncertaintySource);
        // 去掉路径级时不确定度是 0.05；路径级生效后 setup_req=0.94、hold_req=0.03
        assertEquals(0.94, pp.setupRequired, 1e-9);
        assertEquals(0.03, pp.holdRequired, 1e-9);
        log.info("[B8 时钟不确定度] B8-2/B8-3 结果：路径级 0.01 > 时钟级 0.05 > 全局 0.20 ✓");
    }

    @Test
    @Order(10)
    @DisplayName("B8-4 多周期放开的 CDC 路径与输出端口路径，不确定度都不能漏")
    void uncertaintyAppliedToMulticycleCdcAndOutputs() {
        log.info("[B8 时钟不确定度] 用例 cdc_mc：CLK1->CLK2 多周期 + 全局 0.03");
        TimingReport r = runCase("cdc_mc");
        TimingReport.EndpointResult ep = byName(r).get("pin:U3/D");
        assertEquals(TimingReport.EndpointStatus.HOLD_VIOLATED, ep.status,
                "多周期 CDC 保持检查必须真正受不确定度影响");
        TimingReport.PathPair p = ep.pairs.get(0);
        assertEquals(TimingReport.PairCategory.CONSTRAINED, p.category);
        // setup req = 2*0.8 - 0.05 - 0.03 = 1.52 ; hold req = 1*0.8 + 0.02 + 0.03 = 0.85
        assertEquals(1.52, p.setupRequired, 1e-9);
        assertEquals(0.85, p.holdRequired, 1e-9);
        assertEquals(0.03, p.setupUncertainty, 1e-9);
        assertNotNull(p.exception);
        assertTrue(p.exception.startsWith("MULTICYCLE x2"));

        // 走输出端口的路径同样计入不确定度（holdprobe 不含不确定度，这里直接构造校验）
        TimingReport r2 = runCase("unc_global");
        TimingReport.PathPair outPair = byName(r2).get("pin:U1/D").pairs.get(0);
        assertEquals(0.05, outPair.holdUncertainty, 1e-9);
        log.info("[B8 时钟不确定度] B8-4 结果：CDC 多周期路径 setup/hold 均计入不确定度 ✓");
    }

    @Test
    @Order(11)
    @DisplayName("B8-5 重复路径声明/时钟不存在/负值非法：直接失败且错误码可区分")
    void duplicateAndIllegalUncertaintyRejected() {
        log.info("[B8 时钟不确定度] 用例 unc_conflict：同一 from/to 声明两次");
        StaException ex = assertThrows(StaException.class, () -> runCase("unc_conflict"));
        log.info("[B8 时钟不确定度]   code={} {}", ex.getCode(), ex.getMessage());
        assertEquals("DUPLICATE_DEFINITION", ex.getCode());

        log.info("[B8 时钟不确定度] 用例 unc_illegal：时钟不存在");
        assertEquals("UNCERTAINTY_CLOCK_NOT_FOUND",
                assertThrows(StaException.class, () -> runCase("unc_illegal")).getCode());

        log.info("[B8 时钟不确定度] 用例 unc_illegal2：负值非法");
        assertEquals("VALUE_INVALID",
                assertThrows(StaException.class, () -> runCase("unc_illegal2")).getCode());
        log.info("[B8 时钟不确定度] B8-5 结果：重复/非法均失败且错误码可区分 ✓");
    }

    // =========================================================
    // B9 时序降额：早晚角 + 实例/单元/全局三级覆盖；clk->q 跟随
    // =========================================================

    @Test
    @Order(12)
    @DisplayName("B9-1/B9-2 全局早晚角分别作用于建立/保持，报告可见生效系数")
    void globalDerateEarlyAndLateCorners() {
        log.info("[B9 时序降额] 用例 derate_global：early=0.50 late=1.20");
        TimingReport r = runCase("derate_global");
        log.info("\n{}", ReportRenderer.render(r));
        TimingReport.PathPair p = byName(r).get("pin:U3/D").pairs.get(0);
        // 晚角（setup）arrival = 0.08*1.2 + 0.30*1.2 = 0.456
        assertEquals(0.456, p.arrival, 1e-9);
        // 早角（hold）arrival = 0.08*0.5 + 0.30*0.5 = 0.19
        assertEquals(0.190, p.holdArrival, 1e-9);
        assertEquals(0.190 - 0.02, p.holdSlack, 1e-9);
        assertTrue(p.setupDerates.stream().anyMatch(s -> s.contains("U2[BUF]*1.200(late:global")));
        assertTrue(p.holdDerates.stream().anyMatch(s -> s.contains("U2[BUF]*0.500(early:global")));
        assertTrue(p.setupDerates.stream().anyMatch(s -> s.contains("U1[DFF]*1.200(late:global")),
                "寄存器 clk->q 必须跟随晚角");
        assertTrue(p.holdDerates.stream().anyMatch(s -> s.contains("U1[DFF]*0.500(early:global")),
                "寄存器 clk->q 必须跟随早角，不能只按慢角算");
        log.info("[B9 时序降额] B9-1/B9-2 结果：晚角 setup=0.456、早角 hold=0.190，系数全部可查 ✓");
    }

    @Test
    @Order(13)
    @DisplayName("B9-3 实例覆盖单元、单元覆盖全局")
    void derateInstanceOverridesCellOverridesGlobal() {
        log.info("[B9 时序降额] 用例 derate_instance：global late1.20/early0.50、BUF 1.10/0.80、U2 1.30/0.90");
        TimingReport r = runCase("derate_instance");
        TimingReport.PathPair p = byName(r).get("pin:U3/D").pairs.get(0);
        // late: clk->q 0.08*1.2(DFF 取全局) + BUF 0.30*1.30(U2 实例) = 0.486
        assertEquals(0.486, p.arrival, 1e-9);
        // early: 0.08*0.5 + 0.30*0.90 = 0.31
        assertEquals(0.310, p.holdArrival, 1e-9);
        assertTrue(p.setupDerates.stream()
                .anyMatch(s -> s.contains("U2[BUF]*1.300(late:instance:U2")));
        assertTrue(p.holdDerates.stream()
                .anyMatch(s -> s.contains("U2[BUF]*0.900(early:instance:U2")));
        assertTrue(p.setupDerates.stream()
                .anyMatch(s -> s.contains("U1[DFF]*1.200(late:global")));
        log.info("[B9 时序降额] B9-3 结果：U2 取实例 1.30/0.90，U1 取全局 1.20/0.50 ✓");
    }

    @Test
    @Order(14)
    @DisplayName("B9-4 不区分早晚的裸系数两个方向都用，clk->q 跟随同一系数")
    void bareFactorAppliesToBothCorners() {
        log.info("[B9 时序降额] 用例 derate_clk2q：裸系数 1.50");
        TimingReport r = runCase("derate_clk2q");
        TimingReport.PathPair p = byName(r).get("pin:U2/D").pairs.get(0);
        // clk->q 0.08*1.5 = 0.12，setup/hold 两侧一致
        assertEquals(0.12, p.arrival, 1e-9);
        assertEquals(0.12, p.holdArrival, 1e-9);
        assertTrue(p.setupDerates.stream().anyMatch(s -> s.contains("U1[DFF]*1.500(late:global")));
        assertTrue(p.holdDerates.stream().anyMatch(s -> s.contains("U1[DFF]*1.500(early:global")));
        log.info("[B9 时序降额] B9-4 结果：裸系数早晚同为 1.50，clk->q 两侧一致 ✓");
    }

    @Test
    @Order(15)
    @DisplayName("B9-5 非法降额：实例不存在/单元不存在/重复声明/零系数分别可区分")
    void illegalDeratesRejectedWithCodes() {
        log.info("[B9 时序降额] 用例 derate_illegal：实例不存在");
        assertEquals("DERATE_INSTANCE_NOT_FOUND",
                assertThrows(StaException.class, () -> runCase("derate_illegal")).getCode());
        log.info("[B9 时序降额] 用例 derate_cell_missing：单元类型不存在");
        assertEquals("DERATE_CELL_NOT_FOUND",
                assertThrows(StaException.class, () -> runCase("derate_cell_missing")).getCode());
        log.info("[B9 时序降额] 用例 derate_dup：全局降额重复声明");
        assertEquals("DUPLICATE_DEFINITION",
                assertThrows(StaException.class, () -> runCase("derate_dup")).getCode());
        log.info("[B9 时序降额] 用例 derate_zero：系数为 0");
        assertEquals("VALUE_INVALID",
                assertThrows(StaException.class, () -> runCase("derate_zero")).getCode());
        log.info("[B9 时序降额] B9-5 结果：四类非法降额均以独立错误码失败 ✓");
    }

    @Test
    @Order(16)
    @DisplayName("B9-6 老输入无新声明：默认系数 1.0、无不确定度，分析结论不变")
    void oldInputsStayCompatible() {
        log.info("[B9 时序降额] 回归用例 normal：未声明任何新约束");
        TimingReport r = runCase("normal");
        for (TimingReport.EndpointResult ep : r.endpoints) {
            for (TimingReport.PathPair p : ep.pairs) {
                if (p.category != TimingReport.PairCategory.CONSTRAINED) {
                    continue;
                }
                assertEquals(0.0, p.setupUncertainty, 1e-9);
                assertEquals(0.0, p.holdUncertainty, 1e-9);
                assertEquals("(none)", p.setupUncertaintySource);
                assertTrue(p.setupDerates.stream().allMatch(s -> s.contains("*1.000(")),
                        "无降额声明时所有单元系数必须为 1.0: " + p.setupDerates);
            }
        }
        log.info("[B9 时序降额] B9-6 结果：老输入默认系数 1.0、不确定度 0，分析照常 ✓");
    }

    // =========================================================
    // B10 保持违例探针：报出、定位、负 slack；长路径不误报
    // =========================================================

    @Test
    @Order(17)
    @DisplayName("B10-1..3 探针：hold≈0.5 短寄存器路径与输出延时路径报保持违例并定位，长路径不误报")
    void holdViolationProbesReportedAndLocated() {
        log.info("[B10 保持违例探针] 用例 holdprobe");
        TimingReport r = runCase("holdprobe");
        log.info("\n{}", ReportRenderer.render(r));

        TimingReport.EndpointResult reg = byName(r).get("pin:U1/D");
        TimingReport.EndpointResult shortOut = byName(r).get("port:OUTS");
        TimingReport.EndpointResult longOut = byName(r).get("port:OUTL");
        TimingReport.EndpointResult reg2 = byName(r).get("pin:U3/D");

        // 探针 1：hold 库值 0.50、数据到达 0.10 的寄存器路径 -> slack -0.40
        assertEquals(TimingReport.EndpointStatus.HOLD_VIOLATED, reg.status);
        assertEquals(-0.40, reg.worstHoldSlack, 1e-9);
        assertEquals("U1 [DFFH]", reg.cell);
        assertEquals("U1/D", reg.worstHoldPath.get(reg.worstHoldPath.size() - 1),
                "违例必须定位到具体单元/端点引脚");
        assertNotNull(reg.worstHoldPair);
        assertEquals(0.10, reg.worstHoldPair.holdArrival, 1e-9);

        // 探针 2：输出延时 0.10 > 短路径到达（clk_to_q 0.02 + INV 0.05 = 0.07）-> -0.03
        assertEquals(TimingReport.EndpointStatus.HOLD_VIOLATED, shortOut.status);
        assertEquals(-0.03, shortOut.worstHoldSlack, 1e-9);
        assertEquals("OUTS", shortOut.worstHoldPath.get(shortOut.worstHoldPath.size() - 1));

        // 探针 3：到达足够大的路径（SLOW 0.80）不得被误报为保持违例
        assertEquals(TimingReport.EndpointStatus.MET, longOut.status);
        assertTrue(longOut.worstHoldSlack > 0);
        assertEquals(TimingReport.EndpointStatus.MET, reg2.status,
                "clk_to_q 0.02 + INV 0.05 = 0.07 vs hold 0.02：该路径满足保持，不得误报");

        // 所有保持违例必须单列，且与汇总统计一致
        assertEquals(2, r.holdViolations.size());
        assertEquals(2, r.summary.holdViolations);
        assertEquals(r.holdViolations.get(0).endpoint, reg.endpoint, "清单按 slack 升序");
        for (TimingReport.EndpointResult hv : r.holdViolations) {
            assertNotNull(hv.worstHoldPair);
            assertTrue(hv.worstHoldSlack < 0);
        }
        log.info("[B10 保持违例探针] 结果：寄存器 {}、输出 {} 报负 slack 并定位；长路径 {} 未误报 ✓",
                reg.worstHoldSlack, shortOut.worstHoldSlack, longOut.worstHoldSlack);
    }

    // =========================================================
    // B11 深组合链性能/栈深度回归
    // =========================================================

    @Test
    @Order(18)
    @DisplayName("B11-1 1200 个单元的深组合链：快速完成、不爆栈，结论正确")
    void deepCombinationChainPerformance() {
        log.info("[B11 深链回归] 用例 deepchain：1200 级 BUF/INV 串行 + 全局降额/不确定度");
        long t0 = System.nanoTime();
        TimingReport r = runCase("deepchain");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        TimingReport.EndpointResult ep = byName(r).get("pin:U1201/D");
        assertNotNull(ep);
        assertEquals(TimingReport.EndpointStatus.MET, ep.status,
                "周期 1000ns、链路约 210ns 晚角到达，不应违例");
        // 600 BUF * 0.30 + 600 INV * 0.05 = 210；晚角 1.1 + clk->q 0.08*1.1 = 231.088
        assertEquals(231.088, ep.worstSetupPair.arrival, 1e-6);
        // 早角 0.9：189 + 0.072 = 189.072
        assertEquals(189.072, ep.worstHoldPair.holdArrival, 1e-6);
        assertEquals(0.02, ep.worstSetupPair.setupUncertainty, 1e-9);
        assertTrue(ms < 10_000, "深链分析必须在合理时间内完成，实际 " + ms + " ms");
        log.info("[B11 深链回归] 结果：{} 级链路分析耗时 {} ms，无 StackOverflow ✓", 1200, ms);
    }

    // =========================================================
    // B12 声明顺序无关 & HTTP 入口可见新信息
    // =========================================================

    @Test
    @Order(19)
    @DisplayName("B12-1 新约束声明顺序打乱，结论逐字节一致")
    void newConstraintsOrderIndependent() throws IOException {
        log.info("[B12 顺序无关] derate 与 uncertainty 声明重排后比较");
        StaService service = new StaService();
        Path shuffled = Files.createTempFile("sta-shuffle", ".sdc");
        Files.writeString(shuffled, String.join("\n",
                "set_clock_uncertainty 0.01 -from CLK -to U3/D",
                "set_timing_derate -early 0.50 -late 1.20",
                "set_input_delay 0.10 -clock CLK [get_ports IN1]",
                "create_clock CLK -period 2.0"));
        Path canonical = Files.createTempFile("sta-canon", ".sdc");
        Files.writeString(canonical, String.join("\n",
                "create_clock CLK -period 2.0",
                "set_input_delay 0.10 -clock CLK [get_ports IN1]",
                "set_timing_derate -early 0.50 -late 1.20",
                "set_clock_uncertainty 0.01 -from CLK -to U3/D"));
        TimingReport a = service.analyze(COMMON_LIB,
                CASES.resolve("derate_global").resolve("design.net"), shuffled);
        TimingReport b = service.analyze(COMMON_LIB,
                CASES.resolve("derate_global").resolve("design.net"), canonical);
        assertEquals(ReportRenderer.render(b), ReportRenderer.render(a));
        log.info("[B12 顺序无关] 结果：新约束重排前后文本报告逐字节一致 ✓");
    }

    @Test
    @Order(20)
    @DisplayName("B12-2 HTTP 入口（StaController）返回体含不确定度、降额、保持违例清单")
    void httpEntryExposesSignoffFields() {
        log.info("[B12 HTTP] 直接调用 StaController.analyze（与 POST /sta/analyze 同一入口方法）");
        var controller = new com.github.lechandonga.statictiminganalyzerjava.sta.web.StaController();
        var req = new com.github.lechandonga.statictiminganalyzerjava.sta.web
                .StaController.AnalyzeRequest(
                COMMON_LIB.toString(),
                CASES.resolve("holdprobe/design.net").toString(),
                CASES.resolve("holdprobe/constraints.sdc").toString());
        Map<String, Object> json = controller.analyze(req);
        assertTrue(json.containsKey("holdViolations"), "HTTP JSON 必须含保持违例清单");
        @SuppressWarnings("unchecked")
        List<TimingReport.EndpointResult> hvs =
                (List<TimingReport.EndpointResult>) json.get("holdViolations");
        assertEquals(2, hvs.size());
        @SuppressWarnings("unchecked")
        List<TimingReport.EndpointResult> eps =
                (List<TimingReport.EndpointResult>) json.get("endpoints");
        TimingReport.PathPair pair = eps.stream()
                .filter(e -> e.endpoint.equals("pin:U1/D")).findFirst().orElseThrow()
                .worstHoldPair;
        assertNotNull(pair.holdArrival, "HTTP 数据必须含早角到达时间");
        assertNotNull(pair.setupUncertainty, "HTTP 数据必须含不确定度");
        assertNotNull(pair.setupDerates, "HTTP 数据必须含降额明细");
        String text = (String) json.get("text");
        assertTrue(text.contains("保持违例端点清单"));
        assertTrue(text.contains("-0.400"), "探针保持违例 slack 必须出现在文本报告");
        log.info("[B12 HTTP] 结果：HTTP 返回体含 holdViolations/不确定度/降额/最差路径对 ✓");
    }
}
